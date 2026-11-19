// SPDX-License-Identifier: Apache-2.0
package com.filteredann.planner.filter;

import java.util.*;

/** Typed, canonical conjunction. Equality of user labels alone never establishes catalog coverage. */
public record FilterSpec(List<Condition> conditions) {
    public enum Field { RANDOM_RANK, CATEGORY, REGION, CLUSTER_RANK }
    public enum Operator { EQ, LT }
    public record Condition(Field field, Operator operator, int value) {
        public Condition { Objects.requireNonNull(field); Objects.requireNonNull(operator); }
        public String signature() { return field.name()+":"+operator.name()+":"+value; }
    }
    public FilterSpec {
        Objects.requireNonNull(conditions);
        if (conditions.isEmpty() || conditions.size()>16) throw new IllegalArgumentException("Use 1..16 filter conditions");
        conditions=conditions.stream().map(Objects::requireNonNull).distinct().sorted(Comparator.comparing(Condition::signature)).toList();
    }
    public static FilterSpec lessThan(Field field,int value) { return new FilterSpec(List.of(new Condition(field,Operator.LT,value))); }
    public static FilterSpec equalTo(Field field,int value) { return new FilterSpec(List.of(new Condition(field,Operator.EQ,value))); }
    public FilterSpec and(FilterSpec other) { var all=new ArrayList<>(conditions);all.addAll(other.conditions);return new FilterSpec(all); }
    public String signature() { return String.join("&",conditions.stream().map(Condition::signature).toList()); }
    public static FilterSpec known(String id) {
        return switch(id) {
            case "R-rank-100000" -> lessThan(Field.RANDOM_RANK,100000);
            case "R-rank-1000" -> lessThan(Field.RANDOM_RANK,1000);
            case "R-category0-rank-800000" -> equalTo(Field.CATEGORY,0).and(lessThan(Field.RANDOM_RANK,800000));
            case "R-category0-rank-16000" -> equalTo(Field.CATEGORY,0).and(lessThan(Field.RANDOM_RANK,16000));
            case "C-rank-100000" -> lessThan(Field.CLUSTER_RANK,100000);
            case "C-rank-1000" -> lessThan(Field.CLUSTER_RANK,1000);
            case "C-rank100000-random-500000" -> lessThan(Field.CLUSTER_RANK,100000).and(lessThan(Field.RANDOM_RANK,500000));
            case "C-rank100000-random-10000" -> lessThan(Field.CLUSTER_RANK,100000).and(lessThan(Field.RANDOM_RANK,10000));
            default -> throw new IllegalArgumentException("Unknown predefined filter: "+id);
        };
    }
}
