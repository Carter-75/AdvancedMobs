package mod.advancedmobs.util;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the reflected raw-id method lookup for
 * IndexedIterableRawIdFixMixin. This lives outside the mixin package on
 * purpose: the mixin package should contain mixin classes only, and an
 * interface mixin cannot declare its own fields for the mixin transformer
 * to merge.
 */
public final class RawIdMethodCache {
    public static final Map<Class<?>, Method> METHODS = new ConcurrentHashMap<>();

    private RawIdMethodCache() {
    }
}
