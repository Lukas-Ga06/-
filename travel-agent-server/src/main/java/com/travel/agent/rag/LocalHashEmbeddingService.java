package com.travel.agent.rag;

import org.springframework.stereotype.Component;

/**
 * 本地轻量 Embedding：对中文按字符二元组（bigram）做哈希投影到固定维度向量并 L2 归一化。
 * 无需外部服务即可运行语义检索；生产可替换为真实 Embedding 模型。
 */
@Component
public class LocalHashEmbeddingService implements EmbeddingService {

    public static final int DIM = 256;

    @Override
    public float[] embed(String text) {
        double[] vector = new double[DIM];
        if (text == null || text.isBlank()) {
            return new float[DIM];
        }
        String t = text.toLowerCase().replaceAll("\\s+", "");
        for (int i = 0; i < t.length(); i++) {
            // 一元 + 二元组
            int h1 = Math.abs(String.valueOf(t.charAt(i)).hashCode());
            vector[h1 % DIM] += 1.0;
            if (i + 1 < t.length()) {
                int h2 = Math.abs(t.substring(i, i + 2).hashCode());
                vector[h2 % DIM] += 1.5;
            }
        }
        double norm = 0;
        for (double v : vector) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        float[] result = new float[DIM];
        if (norm > 0) {
            for (int i = 0; i < DIM; i++) {
                result[i] = (float) (vector[i] / norm);
            }
        }
        return result;
    }

    @Override
    public int dimension() {
        return DIM;
    }
}
