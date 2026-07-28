/*
 * Copyright (C) 2026, Tencent. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package org.openjdk.bench.java.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Aggregates SM3 JMH JSON result files produced by
 * {@code MessageDigestsSM3} and emits a tabular comparison of the
 * three implementations (pure Java, JNI native, FFM native).
 *
 * Usage:
 * <pre>
 *   java -cp &lt;benchmarks.jar&gt; org.openjdk.bench.java.security.SM3Compare \
 *       --label pureJava --json sm3-java.json \
 *       --label jni     --json sm3-jni.json \
 *       --label ffm     --json sm3-ffm.json
 * </pre>
 *
 * Each JSON file is the standard JMH report. The reader collects the
 * {@code oneShot} benchmark for every {@code length} parameter and
 * reports both nanoseconds per operation and the implied MiB/s.
 */
public final class SM3Compare {

    private record Run(String label, Map<Integer, double[]> lengthToScores) {
    }

    public static void main(String[] args) throws IOException {
        List<String> argv = new ArrayList<>(Arrays.asList(args));
        Map<String, Path> runs = new LinkedHashMap<>();
        for (int i = 0; i < argv.size() - 2; i++) {
            if ("--label".equals(argv.get(i))) {
                String label = argv.get(i + 1);
                String json = argv.get(i + 2);
                runs.put(label, Path.of(json));
            }
        }
        if (runs.isEmpty()) {
            System.err.println("Expected repeating --label <name> --json <path> arguments");
            System.exit(2);
        }

        Map<String, Run> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, Path> entry : runs.entrySet()) {
            parsed.put(entry.getKey(), parseJson(entry.getValue(), entry.getKey()));
        }

        printTable(parsed);
    }

    private static Run parseJson(Path path, String label) throws IOException {
        String raw = Files.readString(path);
        Map<Integer, double[]> lengthToScores = new TreeMap<>();
        JsonReader reader = new JsonReader(raw);
        reader.skipToArray();
        for (Object element : reader.arrayElements()) {
            if (!(element instanceof Map<?, ?> map)) continue;
            String benchmark = (String) map.get("benchmark");
            if (benchmark == null || !benchmark.contains("oneShot")) continue;
            String mode = (String) map.get("mode");
            String scoreUnit = (String) map.get("scoreUnit");
            Object scoreObj = map.get("score");
            Object paramsObj = map.get("params");
            if (mode == null || scoreUnit == null || scoreObj == null || !(paramsObj instanceof Map<?, ?> params)) {
                continue;
            }
            Object lengthObj = params.get("length");
            if (!(lengthObj instanceof Number lengthNum)) continue;
            int length = lengthNum.intValue();
            double score = ((Number) scoreObj).doubleValue();
            double nsPerOp;
            if ("ns/op".equals(scoreUnit) || "ns".equals(scoreUnit)) {
                nsPerOp = score;
            } else if ("MB/s".equalsIgnoreCase(scoreUnit) || "MiB/s".equalsIgnoreCase(scoreUnit)) {
                if (length <= 0) continue;
                nsPerOp = (length / (score * 1024.0 * 1024.0)) * 1e9;
            } else {
                continue;
            }
            lengthToScores.merge(length, new double[]{nsPerOp},
                    (a, b) -> new double[]{a[0] + b[0]});
        }
        return new Run(label, lengthToScores);
    }

    private static void printTable(Map<String, Run> runs) {
        List<String> labels = new ArrayList<>(runs.keySet());
        List<Integer> lengths = new ArrayList<>();
        for (Run run : runs.values()) {
            lengths.addAll(run.lengthToScores().keySet());
        }
        Collections.sort(lengths);

        Run pureJava = runs.get("pureJava");
        double[] baseline = new double[lengths.size()];
        for (int i = 0; i < lengths.size(); i++) {
            int len = lengths.get(i);
            double[] scores = pureJava == null ? null : pureJava.lengthToScores().get(len);
            baseline[i] = scores == null ? Double.NaN : scores[0];
        }

        StringBuilder header = new StringBuilder("%-10s");
        for (String label : labels) {
            header.append(String.format(" %-22s", label + " (ns/op)"));
            header.append(String.format(" %-12s", label + " speedup"));
        }
        System.out.printf(header + "%n", "Length");

        StringBuilder row = new StringBuilder("%-10d");
        for (int n = 0; n < labels.size(); n++) {
            row.append(" %-22.2f %-12s");
        }
        for (int i = 0; i < lengths.size(); i++) {
            int len = lengths.get(i);
            Object[] values = new Object[labels.size() * 2 + 1];
            values[0] = len;
            for (int n = 0; n < labels.size(); n++) {
                double[] scores = runs.get(labels.get(n)).lengthToScores().get(len);
                if (scores == null) {
                    values[n * 2 + 1] = "-";
                    values[n * 2 + 2] = "-";
                    continue;
                }
                double speedup = Double.isNaN(baseline[i]) || scores[0] == 0
                        ? Double.NaN
                        : baseline[i] / scores[0];
                values[n * 2 + 1] = scores[0];
                values[n * 2 + 2] = Double.isNaN(speedup)
                        ? "-"
                        : String.format(Locale.ROOT, "%.2fx", speedup);
            }
            System.out.printf(row.toString() + "%n", values);
        }
    }

    /**
     * Minimal JSON parser that supports the subset of JSON emitted by JMH:
     * objects with string keys and string/number/array/object values, and
     * top-level arrays of objects. The parser is intentionally small and
     * not for general use.
     */
    private static final class JsonReader {
        private final String input;
        private int pos;

        JsonReader(String input) {
            this.input = input;
            this.pos = 0;
        }

        void skipToArray() {
            skipWhitespace();
            consume('[');
        }

        List<Object> arrayElements() {
            List<Object> result = new ArrayList<>();
            while (true) {
                skipWhitespace();
                if (peek() == ']') {
                    pos++;
                    return result;
                }
                result.add(readValue());
                skipWhitespace();
                if (peek() == ',') {
                    pos++;
                    continue;
                }
                if (peek() == ']') {
                    pos++;
                    return result;
                }
                throw new IllegalStateException("Unexpected character at " + pos);
            }
        }

        private Object readValue() {
            skipWhitespace();
            char c = peek();
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't', 'f' -> readBoolean();
                case 'n' -> readNull();
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            consume('{');
            Map<String, Object> map = new LinkedHashMap<>();
            while (true) {
                skipWhitespace();
                if (peek() == '}') {
                    pos++;
                    return map;
                }
                String key = readString();
                skipWhitespace();
                consume(':');
                Object value = readValue();
                map.put(key, value);
                skipWhitespace();
                if (peek() == ',') {
                    pos++;
                    continue;
                }
                if (peek() == '}') {
                    pos++;
                    return map;
                }
                throw new IllegalStateException("Unexpected character at " + pos);
            }
        }

        private List<Object> readArray() {
            consume('[');
            List<Object> result = new ArrayList<>();
            while (true) {
                skipWhitespace();
                if (peek() == ']') {
                    pos++;
                    return result;
                }
                result.add(readValue());
                skipWhitespace();
                if (peek() == ',') {
                    pos++;
                    continue;
                }
                if (peek() == ']') {
                    pos++;
                    return result;
                }
                throw new IllegalStateException("Unexpected character at " + pos);
            }
        }

        private String readString() {
            consume('"');
            StringBuilder sb = new StringBuilder();
            while (pos < input.length()) {
                char c = input.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\' && pos < input.length()) {
                    char next = input.charAt(pos++);
                    sb.append(switch (next) {
                        case '"' -> '"';
                        case '\\' -> '\\';
                        case '/' -> '/';
                        case 'b' -> '\b';
                        case 'f' -> '\f';
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case 'u' -> {
                            StringBuilder hex = new StringBuilder();
                            for (int k = 0; k < 4 && pos < input.length(); k++) {
                                hex.append(input.charAt(pos++));
                            }
                            yield (char) Integer.parseInt(hex.toString(), 16);
                        }
                        default -> next;
                    });
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalStateException("Unterminated string");
        }

        private Boolean readBoolean() {
            if (input.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (input.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalStateException("Invalid literal at " + pos);
        }

        private Object readNull() {
            if (input.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalStateException("Invalid literal at " + pos);
        }

        private Number readNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < input.length()
                    && "0123456789.+-eE".indexOf(input.charAt(pos)) >= 0) {
                pos++;
            }
            String slice = input.substring(start, pos);
            if (slice.contains(".") || slice.contains("e") || slice.contains("E")) {
                return Double.parseDouble(slice);
            }
            return Long.parseLong(slice);
        }

        private void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }

        private char peek() {
            skipWhitespace();
            return input.charAt(pos);
        }

        private void consume(char expected) {
            skipWhitespace();
            if (input.charAt(pos) != expected) {
                throw new IllegalStateException("Expected " + expected + " at " + pos);
            }
            pos++;
        }
    }
}
