package dev.flowlens.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Strict loading avoids silently accepting misspelled security policy fields. */
public final class Rules {
    private Rules() {}
    public static List<Rule> load(Path path) throws IOException {
        JsonArray array;
        try (var reader = Files.newBufferedReader(path)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonArray()) throw new IllegalArgumentException("Rules must be a JSON array");
            array = root.getAsJsonArray();
        }
        if (array.isEmpty()) throw new IllegalArgumentException("Rules must not be empty");
        List<Rule> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> fields = Set.of("id", "kind", "owner", "name", "descriptor", "argument");
        Gson gson = new Gson();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("Each rule must be an object");
            JsonObject object = element.getAsJsonObject();
            if (!object.keySet().equals(fields)) throw new IllegalArgumentException("Each rule needs exactly " + fields);
            for (String field : fields) {
                var value = object.get(field);
                if (!value.isJsonPrimitive()) throw new IllegalArgumentException("Invalid rule field: " + field);
                if (field.equals("argument")) {
                    if (!value.getAsJsonPrimitive().isNumber() || !value.getAsString().matches("-?[0-9]+"))
                        throw new IllegalArgumentException("argument must be an integer");
                } else if (!value.getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException(field + " must be a string");
                }
            }
            Rule rule = gson.fromJson(object, Rule.class);
            if (!ids.add(rule.id())) throw new IllegalArgumentException("Duplicate rule id: " + rule.id());
            result.add(rule);
        }
        return List.copyOf(result);
    }
}
