package pub.lantian.symfoniumlyricprovider;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

final class ReflectionAccess {
    private ReflectionAccess() {
    }

    static List<Field> instanceFields(Class<?> type) {
        /*
         * Structural detection scans many unrelated Symfonium classes. Some of
         * those classes reference framework or library types that are not resolvable
         * from the process class loader at hook-install time. ART may throw
         * NoClassDefFoundError from getDeclaredFields() or Field#getType() while it
         * resolves that metadata. Treat that as "this class cannot be inspected"
         * instead of aborting hook installation for the whole module.
         */
        ArrayList<Field> fields = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            Field[] declaredFields;
            try {
                declaredFields = current.getDeclaredFields();
            } catch (Throwable ignored) {
                current = superclass(current);
                continue;
            }

            for (Field field : declaredFields) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                }
                fields.add(field);
            }
            current = superclass(current);
        }
        return fields;
    }

    static Class<?> fieldType(Field field) {
        try {
            return field.getType();
        } catch (Throwable ignored) {
            return null;
        }
    }

    static Object fieldValue(Field field, Object instance) {
        try {
            return field.get(instance);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Class<?> superclass(Class<?> type) {
        try {
            return type.getSuperclass();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
