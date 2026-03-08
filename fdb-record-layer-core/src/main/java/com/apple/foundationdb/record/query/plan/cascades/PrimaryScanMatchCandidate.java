/*
 * MatchCandidate.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2020 Apple Inc. and the FoundationDB project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.apple.foundationdb.record.query.plan.cascades;

import com.apple.foundationdb.record.metadata.RecordType;
import com.apple.foundationdb.record.metadata.expressions.KeyExpression;
import com.apple.foundationdb.record.query.expressions.Comparisons;
import com.apple.foundationdb.record.query.plan.ScanComparisons;
import com.apple.foundationdb.record.query.plan.cascades.typing.Type;
import com.apple.foundationdb.record.query.plan.cascades.values.Value;
import com.apple.foundationdb.record.query.plan.plans.RecordQueryPlan;
import com.apple.foundationdb.record.query.plan.plans.RecordQueryScanPlan;
import com.apple.foundationdb.record.query.plan.plans.RecordQueryTypeFilterPlan;
import com.google.common.base.Suppliers;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Case class to represent a match candidate that is backed by an index.
 */
public class PrimaryScanMatchCandidate implements MatchCandidate, ValueIndexLikeMatchCandidate, WithPrimaryKeyMatchCandidate {
    /**
     * Holds the parameter names for all necessary parameters that need to be bound during matching.
     */
    @Nonnull
    private final List<CorrelationIdentifier> parameters;

    /**
     * Traversal object of the primary scan graph (not the query graph).
     */
    @Nonnull
    private final Traversal traversal;

    /**
     * Set of record types that are available in the context of the query.
     */
    @Nonnull
    private final List<RecordType> availableRecordTypes;

    /**
     * Set of record types that are actually queried.
     */
    @Nonnull
    private final List<RecordType> queriedRecordTypes;

    @Nonnull
    private final KeyExpression primaryKey;

    @Nonnull
    private final Type.Record baseType;

    @Nonnull
    private final Supplier<Optional<List<Value>>> primaryKeyValuesSupplier;

    public PrimaryScanMatchCandidate(@Nonnull final Traversal traversal,
                                     @Nonnull final List<CorrelationIdentifier> parameters,
                                     @Nonnull final Collection<RecordType> availableRecordTypes,
                                     @Nonnull final Collection<RecordType> queriedRecordTypes,
                                     @Nonnull final KeyExpression primaryKey,
                                     @Nonnull final Type.Record baseType) {
        this.traversal = traversal;
        this.parameters = ImmutableList.copyOf(parameters);
        this.availableRecordTypes = ImmutableList.copyOf(availableRecordTypes);
        this.queriedRecordTypes = ImmutableList.copyOf(queriedRecordTypes);
        this.primaryKey = primaryKey;
        this.baseType = baseType;
        this.primaryKeyValuesSupplier = Suppliers.memoize(() -> MatchCandidate.computePrimaryKeyValuesMaybe(primaryKey, baseType));
    }

    @Nonnull
    @Override
    public String getName() {
        return "primary(" + String.join(",", getAvailableRecordTypeNames()) + ")";
    }

    @Nonnull
    @Override
    public Traversal getTraversal() {
        return traversal;
    }

    @Nonnull
    @Override
    public List<CorrelationIdentifier> getSargableAliases() {
        return parameters;
    }

    @Nonnull
    @Override
    public List<CorrelationIdentifier> getOrderingAliases() {
        return getSargableAliases();
    }

    @Nonnull
    @Override
    public Type.Record getBaseType() {
        return baseType;
    }

    @Nonnull
    public List<RecordType> getAvailableRecordTypes() {
        return availableRecordTypes;
    }

    @Nonnull
    public Set<String> getAvailableRecordTypeNames() {
        return getAvailableRecordTypes().stream()
                .map(RecordType::getName)
                .collect(ImmutableSet.toImmutableSet());
    }

    @Nonnull
    @Override
    public List<RecordType> getQueriedRecordTypes() {
        return queriedRecordTypes;
    }

    @Nonnull
    @Override
    public Optional<List<Value>> getPrimaryKeyValuesMaybe() {
        return primaryKeyValuesSupplier.get();
    }

    @Nonnull
    @Override
    public KeyExpression getFullKeyExpression() {
        return primaryKey;
    }

    @Override
    public String toString() {
        return "primary[" + String.join(",", getQueriedRecordTypeNames()) + "]";
    }

    @Override
    public boolean createsDuplicates() {
        return false;
    }

    @Override
    public int getColumnSize() {
        return primaryKey.getColumnSize();
    }

    @Override
    public boolean isUnique() {
        return true;
    }

    /**
     * Overrides the default {@link MatchCandidate#computeBoundParameterPrefixMap} to handle the common case where
     * the primary key starts with a {@code recordType()} component (non-intermingled schemas). In that case, the type
     * restriction is expressed via a {@code LogicalTypeFilterExpression} rather than a WHERE predicate, so the first
     * sargable alias is never present in the parameter binding map and the default implementation would return an empty
     * map, resulting in a full-table scan.
     *
     * <p>When there is exactly one queried record type and its primary key starts with {@code recordType()}, this
     * method injects an implicit equality range for that first alias (keyed to the record type's discriminator value)
     * and then continues the standard prefix-scan logic for the remaining user-defined PK columns.</p>
     */
    @Nonnull
    @Override
    public Map<CorrelationIdentifier, ComparisonRange> computeBoundParameterPrefixMap(@Nonnull final MatchInfo matchInfo) {
        final var parameterBindingMap = matchInfo.getRegularMatchInfo().getParameterBindingMap();
        final var prefixMap = Maps.<CorrelationIdentifier, ComparisonRange>newHashMap();

        int startIdx = 0;
        // For non-intermingled schemas the first PK component is recordType(). The type restriction
        // comes from LogicalTypeFilterExpression, not a WHERE predicate, so the first alias is never
        // in parameterBindingMap. When exactly one type is queried, inject an implicit equality for
        // the recordType() discriminator so downstream code can produce a bounded range scan.
        if (queriedRecordTypes.size() == 1
                && queriedRecordTypes.get(0).primaryKeyHasRecordTypePrefix()
                && !parameters.isEmpty()
                && !parameterBindingMap.containsKey(parameters.get(0))) {
            final var typeKey = queriedRecordTypes.get(0).getRecordTypeKey();
            prefixMap.put(parameters.get(0), ComparisonRange.from(
                    new Comparisons.SimpleComparison(Comparisons.Type.EQUALS, typeKey)));
            startIdx = 1;
        }

        for (int i = startIdx; i < parameters.size(); i++) {
            final var parameter = parameters.get(i);
            final var comparisonRange = parameterBindingMap.get(parameter);
            if (comparisonRange == null) {
                return ImmutableMap.copyOf(prefixMap);
            }
            if (prefixMap.containsKey(parameter)) {
                Verify.verify(prefixMap.get(parameter).equals(comparisonRange));
                continue;
            }
            switch (comparisonRange.getRangeType()) {
                case EQUALITY:
                    prefixMap.put(parameter, comparisonRange);
                    break;
                case INEQUALITY:
                    prefixMap.put(parameter, comparisonRange);
                    return ImmutableMap.copyOf(prefixMap);
                case EMPTY:
                default:
                    return ImmutableMap.copyOf(prefixMap);
            }
        }
        return ImmutableMap.copyOf(prefixMap);
    }

    @Nonnull
    @Override
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    public RecordQueryPlan toEquivalentPlan(@Nonnull PartialMatch partialMatch,
                                            @Nonnull final PlanContext planContext,
                                            @Nonnull final Memoizer memoizer,
                                            @Nonnull final List<ComparisonRange> comparisonRanges,
                                            final boolean reverseScanOrder) {
        final var availableRecordTypeNames = getAvailableRecordTypeNames();
        final var queriedRecordTypeNames = getQueriedRecordTypeNames();
        Verify.verify(availableRecordTypeNames.containsAll(queriedRecordTypeNames));

        RecordQueryScanPlan scanPlan;
        if (queriedRecordTypeNames.size() == availableRecordTypeNames.size()) {
            scanPlan =
                    new RecordQueryScanPlan(availableRecordTypeNames,
                            baseType,
                            primaryKey,
                            toScanComparisons(comparisonRanges),
                            reverseScanOrder,
                            false,
                            this);
            return scanPlan;
        } else {
            scanPlan =
                    new RecordQueryScanPlan(availableRecordTypeNames,
                            new Type.AnyRecord(false),
                            primaryKey,
                            toScanComparisons(comparisonRanges),
                            reverseScanOrder,
                            false,
                            this);

            return new RecordQueryTypeFilterPlan(
                    Quantifier.physical(memoizer.memoizePlan(scanPlan)),
                    queriedRecordTypeNames,
                    baseType);
        }
    }

    @Nonnull
    private static ScanComparisons toScanComparisons(@Nonnull List<ComparisonRange> comparisonRanges) {
        ScanComparisons.Builder builder = new ScanComparisons.Builder();
        for (ComparisonRange comparisonRange : comparisonRanges) {
            builder.addComparisonRange(comparisonRange);
        }
        return builder.build();
    }
}
