package com.travel.agent.rag;

import org.springframework.stereotype.Component;

/** 向量 JSON 编解码 */
@Component
public class EmbeddingCodec {

    public String serialize(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(Math.round(vector[i] * 10000f) / 10000f);
        }
        return sb.append("]").toString();
    }

    public float[] parse(String json) {
        String body = json.replace("[", "").replace("]", "");
        if (body.isBlank()) {
            return new float[0];
        }
        String[] parts = body.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i].trim());
        }
        return vector;
    }
}
