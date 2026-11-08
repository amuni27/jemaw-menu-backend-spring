package com.agafari.com.jobs;

import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** AGF SUB-R2-08: the canonical form of a job payload (object keys sorted at every depth, no whitespace) and its SHA-256. */
public final class PayloadHash {

    private PayloadHash() {
    }

    public static String of(JsonNode node) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(node).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String canonical(JsonNode node) {
        StringBuilder out = new StringBuilder();
        write(node, out);
        return out.toString();
    }

    private static void write(JsonNode node, StringBuilder out) {
        if (node.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonNode> e : node.properties()) sorted.put(e.getKey(), e.getValue());
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> e : sorted.entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append(tools.jackson.databind.node.JsonNodeFactory.instance.stringNode(e.getKey())).append(':');
                write(e.getValue(), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            boolean first = true;
            for (JsonNode element : node) {
                if (!first) out.append(',');
                first = false;
                write(element, out);
            }
            out.append(']');
        } else {
            out.append(node);
        }
    }
}
