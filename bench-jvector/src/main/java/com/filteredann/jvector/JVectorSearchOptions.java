package com.filteredann.jvector;

public record JVectorSearchOptions(int topK, int candidateK) {
    public JVectorSearchOptions {
        if (topK <= 0 || candidateK < topK) throw new IllegalArgumentException("Require candidateK >= topK > 0");
    }
    public int rerankK() { return candidateK; }
}
