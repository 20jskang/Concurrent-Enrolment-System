import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SimpleJson - a minimal, dependency-free JSON encoder/decoder.
 *
 * This exists purely so that ProtocolMessage does not require an external
 * JSON library (Gson, Jackson, org.json, ...) to be added to your build.
 * You are not expected to read or modify this file. Use ProtocolMessage
 * instead, which gives you a typed API for the actual protocol messages
 * this assignment defines.
 *
 * Decoded JSON values are represented using plain Java types:
 *   object -> LinkedHashMap<String, Object>   (insertion order preserved)
 *   array  -> ArrayList<Object>
 *   string -> String
 *   number -> Long (if it parses as an integer) or Double otherwise
 *   true/false -> Boolean
 *   null   -> null
 */
final class SimpleJson {

    private SimpleJson() { }

    // ==================== Encoding ====================

    static String encode(Object value) {
        StringBuilder sb = new StringBuilder();
        encodeValue(value, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void encodeValue(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            encodeString((String) value, sb);
        } else if (value instanceof Map) {
            encodeObject((Map<String, Object>) value, sb);
        } else if (value instanceof List) {
            encodeArray((List<Object>) value, sb);
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value.toString());
        } else {
            // Fallback: treat anything else as its string form.
            encodeString(value.toString(), sb);
        }
    }

    private static void encodeObject(Map<String, Object> map, StringBuilder sb) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            encodeString(entry.getKey(), sb);
            sb.append(':');
            encodeValue(entry.getValue(), sb);
        }
        sb.append('}');
    }

    private static void encodeArray(List<Object> list, StringBuilder sb) {
        sb.append('[');
        boolean first = true;
        for (Object item : list) {
            if (!first) sb.append(',');
            first = false;
            encodeValue(item, sb);
        }
        sb.append(']');
    }

    private static void encodeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ==================== Decoding ====================

    static Object decode(String json) throws JsonParseException {
        Parser parser = new Parser(json);
        parser.skipWhitespace();
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new JsonParseException("Unexpected trailing content in JSON at position " + parser.pos);
        }
        return value;
    }

    static final class JsonParseException extends Exception {
        JsonParseException(String message) { super(message); }
    }

    private static final class Parser {
        private final String s;
        private int pos = 0;

        Parser(String s) { this.s = s; }

        boolean atEnd() { return pos >= s.length(); }

        char peek() throws JsonParseException {
            if (atEnd()) throw new JsonParseException("Unexpected end of JSON input");
            return s.charAt(pos);
        }

        void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        Object parseValue() throws JsonParseException {
            skipWhitespace();
            char c = peek();
            switch (c) {
                case '{': return parseObject();
                case '[': return parseArray();
                case '"': return parseString();
                case 't': expectLiteral("true"); return Boolean.TRUE;
                case 'f': expectLiteral("false"); return Boolean.FALSE;
                case 'n': expectLiteral("null"); return null;
                default:  return parseNumber();
            }
        }

        Map<String, Object> parseObject() throws JsonParseException {
            Map<String, Object> map = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (peek() == '}') { pos++; return map; }
            while (true) {
                skipWhitespace();
                if (peek() != '"') throw new JsonParseException("Expected string key at position " + pos);
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                char c = peek();
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; break; }
                throw new JsonParseException("Expected ',' or '}' at position " + pos);
            }
            return map;
        }

        List<Object> parseArray() throws JsonParseException {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (peek() == ']') { pos++; return list; }
            while (true) {
                Object value = parseValue();
                list.add(value);
                skipWhitespace();
                char c = peek();
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; break; }
                throw new JsonParseException("Expected ',' or ']' at position " + pos);
            }
            return list;
        }

        String parseString() throws JsonParseException {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) throw new JsonParseException("Unterminated string starting before position " + pos);
                char c = s.charAt(pos++);
                if (c == '"') break;
                if (c == '\\') {
                    if (atEnd()) throw new JsonParseException("Unterminated escape sequence");
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case '"':  sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/':  sb.append('/'); break;
                        case 'b':  sb.append('\b'); break;
                        case 'f':  sb.append('\f'); break;
                        case 'n':  sb.append('\n'); break;
                        case 'r':  sb.append('\r'); break;
                        case 't':  sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 > s.length()) throw new JsonParseException("Invalid unicode escape");
                            String hex = s.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                            break;
                        default:
                            throw new JsonParseException("Invalid escape character '\\" + esc + "' at position " + pos);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Object parseNumber() throws JsonParseException {
            int start = pos;
            if (!atEnd() && s.charAt(pos) == '-') pos++;
            while (!atEnd() && Character.isDigit(s.charAt(pos))) pos++;
            boolean isDouble = false;
            if (!atEnd() && s.charAt(pos) == '.') {
                isDouble = true;
                pos++;
                while (!atEnd() && Character.isDigit(s.charAt(pos))) pos++;
            }
            if (!atEnd() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                isDouble = true;
                pos++;
                if (!atEnd() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                while (!atEnd() && Character.isDigit(s.charAt(pos))) pos++;
            }
            String num = s.substring(start, pos);
            if (num.isEmpty() || "-".equals(num)) {
                throw new JsonParseException("Invalid number at position " + start);
            }
            try {
                return isDouble ? (Object) Double.parseDouble(num) : (Object) Long.parseLong(num);
            } catch (NumberFormatException e) {
                throw new JsonParseException("Invalid number '" + num + "' at position " + start);
            }
        }

        void expect(char expected) throws JsonParseException {
            if (atEnd() || s.charAt(pos) != expected) {
                throw new JsonParseException("Expected '" + expected + "' at position " + pos);
            }
            pos++;
        }

        void expectLiteral(String literal) throws JsonParseException {
            if (pos + literal.length() > s.length() || !s.regionMatches(pos, literal, 0, literal.length())) {
                throw new JsonParseException("Expected '" + literal + "' at position " + pos);
            }
            pos += literal.length();
        }
    }
}
