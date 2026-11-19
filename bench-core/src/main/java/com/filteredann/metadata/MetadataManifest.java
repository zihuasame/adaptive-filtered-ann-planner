package com.filteredann.metadata;

import java.util.Map;

public record MetadataManifest(int formatVersion, int recordCount, long randomSeed,
        String randomAlgorithm, String algorithmVersion, Map<String, String> fieldDefinitions,
        Map<String, Integer> fieldCardinalities, Map<String, String> datasetSha256,
        String metadataSha256) { }
