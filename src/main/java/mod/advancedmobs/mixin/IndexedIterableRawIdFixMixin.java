package mod.advancedmobs.mixin;

import mod.advancedmobs.AdvancedMobsMod;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.Registry;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Global compatibility fix for registry-entry serialization.
 *
 * Some mods (UMW included) may end up with ItemStacks whose enchantments component contains
 * {@link Holder.Reference} instances that are valid but originate from a different
 * registry wrapper than the one used by the networking buffer.
 *
 * When another mod (e.g. AdvancedLootInfo + Polymer) encodes an ItemStack during world load,
 * Minecraft may call {@link IdMap#getRawIdOrThrow(Object)} and throw:
 * "Can't find id for 'Reference{...}'".
 *
 * This mixin intercepts that method and, if the raw id lookup fails for a reference entry,
 * attempts to resolve an equivalent reference by {@link ResourceKey} against the current
 * iterable implementation before retrying.
 */
@Mixin(IdMap.class)
public interface IndexedIterableRawIdFixMixin {

    // The method cache lives in RawIdMethodCache. Interface mixins cannot
    // declare their own fields.

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(method = "getIdOrThrow(Ljava/lang/Object;)I", at = @At("HEAD"), cancellable = true)
    private void umw$fixForeignRegistryEntry(Object value, CallbackInfoReturnable<Integer> cir) {
        AdvancedMobsMod.runSafely("IndexedIterableRawIdFixMixin#fixForeignRegistryEntry", () -> {
            IdMap self = (IdMap) (Object) this;

            // Fast path: if this iterable can already resolve it, do nothing.
            Integer rawId = tryGetRawId(self, value);
            if (rawId != null && rawId.intValue() >= 0) {
                cir.setReturnValue(rawId);
                return;
            }

            if (!(value instanceof Holder.Reference<?> reference)) {
                return;
            }

            ResourceKey<?> key;
            try {
                key = reference.key();
            } catch (Exception ignored) {
                key = null;
            }
            if (key == null) {
                return;
            }

            Holder.Reference<?> resolved = tryResolveReference(self, key);
            if (resolved == null) {
                return;
            }

            Integer resolvedRawId = tryGetRawId(self, resolved);
            if (resolvedRawId != null && resolvedRawId.intValue() >= 0) {
                cir.setReturnValue(resolvedRawId);
            }
        });
    }

    @SuppressWarnings({"rawtypes"})
    private static Integer tryGetRawId(IdMap self, Object value) {
        if (self == null || value == null) {
            return null;
        }

        // Fast path: reuse the method resolved for this class on an earlier call.
        Method cached = RawIdMethodCache.METHODS.get(self.getClass());
        if (cached != null) {
            try {
                Object result = cached.invoke(self, value);
                if (result instanceof Integer i) {
                    return i;
                }
            } catch (Exception ignored) {
                // Fall through to the full resolution below.
            }
        }

        try {
            // IdMap has a getRawId(T) method; use reflection to avoid signature churn.
            Method method = self.getClass().getMethod("getId", Object.class);
            RawIdMethodCache.METHODS.put(self.getClass(), method);
            Object result = method.invoke(self, value);
            if (result instanceof Integer i) {
                return i;
            }
        } catch (NoSuchMethodException ignored) {
            // Some impls declare getRawId with the generic erasure but not Object.class param;
            // fall through to the untyped search.
        } catch (Exception ignored) {
            return null;
        }

        // Fallback: find any getRawId(*) method returning int.
        try {
            for (Method m : self.getClass().getMethods()) {
                if (!"getId".equals(m.getName())) {
                    continue;
                }
                if (m.getParameterCount() != 1 || m.getReturnType() != int.class) {
                    continue;
                }
                RawIdMethodCache.METHODS.put(self.getClass(), m);
                Object result = m.invoke(self, value);
                return (Integer) result;
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    @SuppressWarnings({"rawtypes"})
    private static Holder.Reference<?> tryResolveReference(IdMap self, ResourceKey<?> key) {
        if (self == null || key == null) {
            return null;
        }

        // Many registry wrappers used in networking expose getEntry(ResourceKey) -> Optional.
        for (String methodName : new String[] {"getEntry", "getOptional", "get", "getOrEmpty"}) {
            try {
                Method method = self.getClass().getMethod(methodName, ResourceKey.class);
                if (!Optional.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                Object result = method.invoke(self, key);
                if (!(result instanceof Optional<?> optional) || optional.isEmpty()) {
                    continue;
                }
                Object entry = optional.get();
                if (entry instanceof Holder.Reference<?> reference) {
                    return reference;
                }
            } catch (NoSuchMethodException ignored) {
                // try next
            } catch (Exception ignored) {
                // fall through
            }
        }

        // Fallback: handle Registry$1 (anonymous inner class capturing the outer Registry).
        try {
            Registry<?> outerRegistry = tryGetOuterRegistry(self);
            if (outerRegistry != null) {
                Optional<? extends Holder.Reference<?>> resolved = outerRegistry.get((ResourceKey) key);
                return resolved.orElse(null);
            }
        } catch (Exception ignored) {
            return null;
        }

        return null;
    }

    private static Registry<?> tryGetOuterRegistry(IdMap self) {
        try {
            for (Field field : self.getClass().getDeclaredFields()) {
                if (!Registry.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(self);
                if (value instanceof Registry<?> registry) {
                    return registry;
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }
}

/**
 * Holds the reflected raw-id lookup, outside the interface mixin.
 */
final class RawIdMethodCache {
    static final java.util.Map<Class<?>, Method> METHODS = new java.util.concurrent.ConcurrentHashMap<>();

    private RawIdMethodCache() {
    }
}
