package com.filteredann.index;

public record SearchStats(long predicateMatches, long distanceCalculations, long elapsedNanos) { }
