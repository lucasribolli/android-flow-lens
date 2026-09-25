package dev.flowlens.core;

import java.util.List;
import java.util.Objects;
import org.objectweb.asm.Type;

/** An exact JVM method source (return value) or sink (zero-based argument). */
public record Rule(String id, String kind, String owner, String name, String descriptor, int argument) {
    public Rule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        if (id.isBlank() || owner.isBlank() || name.isBlank()) {
            throw new IllegalArgumentException("Rule id, owner and name must not be blank");
        }
        if (!kind.equals("source") && !kind.equals("sink")) {
            throw new IllegalArgumentException("Rule kind must be source or sink: " + id);
        }
        if (owner.contains(".")) {
            throw new IllegalArgumentException("Use JVM internal owner names with / separators: " + owner);
        }
        Type[] arguments;
        Type result;
        try {
            if (!descriptor.startsWith("(")) throw new IllegalArgumentException("Missing method argument list");
            arguments = Type.getArgumentTypes(descriptor);
            result = Type.getReturnType(descriptor);
            if (!Type.getMethodDescriptor(result, arguments).equals(descriptor)) {
                throw new IllegalArgumentException("Trailing or noncanonical descriptor tokens");
            }
            for (Type type : arguments) validateFieldType(type, false);
            validateFieldType(result, true);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid JVM method descriptor for rule " + id + ": " + descriptor, error);
        }
        if (kind.equals("source") && (argument != -1 || result.equals(Type.VOID_TYPE))) {
            throw new IllegalArgumentException("Source rules require argument=-1 and a non-void return: " + id);
        }
        if (kind.equals("sink") && (argument < 0 || argument >= arguments.length)) {
            throw new IllegalArgumentException("Sink argument is outside the method arguments: " + id);
        }
    }

    private static void validateFieldType(Type type, boolean allowVoid) {
        if (type.getSort() == Type.VOID && allowVoid) return;
        if (type.getSort() == Type.VOID || type.getSort() == Type.METHOD) {
            throw new IllegalArgumentException("Void/method type in a value position");
        }
        if (type.getSort() == Type.ARRAY) {
            if (type.getDimensions() > 255) throw new IllegalArgumentException("Array exceeds JVM dimension limit");
            validateFieldType(type.getElementType(), false);
        }
        if (type.getSort() == Type.OBJECT) {
            String internalName = type.getInternalName();
            for (String part : internalName.split("/", -1)) {
                if (part.isEmpty() || part.indexOf('.') >= 0 || part.indexOf(';') >= 0 || part.indexOf('[') >= 0) {
                    throw new IllegalArgumentException("Invalid object type name");
                }
            }
        }
    }

    public boolean matches(String callOwner, String callName, String callDescriptor) {
        return owner.equals(callOwner) && name.equals(callName) && descriptor.equals(callDescriptor);
    }

    /** Illustrative rules, not a complete Android vulnerability policy. */
    public static List<Rule> defaults() {
        return List.of(
                new Rule("intent-string-extra", "source", "android/content/Intent", "getStringExtra",
                        "(Ljava/lang/String;)Ljava/lang/String;", -1),
                new Rule("sql-exec", "sink", "android/database/sqlite/SQLiteDatabase", "execSQL",
                        "(Ljava/lang/String;)V", 0),
                new Rule("sql-exec-bind", "sink", "android/database/sqlite/SQLiteDatabase", "execSQL",
                        "(Ljava/lang/String;[Ljava/lang/Object;)V", 0),
                new Rule("webview-url", "sink", "android/webkit/WebView", "loadUrl",
                        "(Ljava/lang/String;)V", 0),
                new Rule("runtime-exec", "sink", "java/lang/Runtime", "exec",
                        "(Ljava/lang/String;)Ljava/lang/Process;", 0));
    }
}
