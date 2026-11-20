package mod.advancedmobs.mixin;

import mod.advancedmobs.AdvancedMobsMod;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.Registry;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Compatibility fix for cases where another mod constructs ItemStacks containing
 * {@link Holder.Reference} instances originating from a different registry wrapper.
 *
 * During packet encoding, Minecraft encodes reference entries by raw id via
 * {@link IdMap#getRawIdOrThrow(Object)}. If the reference object isn't the exact
 * instance held by the current buffer's registry wrapper, encoding throws:
 * "Can't find id for 'Reference{...}'".
 *
 * This mixin redirects the raw-id lookup and, on failure, attempts to resolve an equivalent
 * reference entry by {@link ResourceKey} from the current wrapper before retrying.
 */
@Mixin(targets = "net.minecraft.network.codec.PacketCodecs$16")
public final class RegistryEntryEncodingFixMixin {

    @Redirect(
        method = "encode",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/util/collection/IdMap;getRawIdOrThrow(Ljava/lang/Object;)I"
        ),
        require = 0
    )
    @SuppressWarnings({"rawtypes", "unchecked"})
    private int umw$resolveRawIdOrThrow(IdMap iterable, Object value) {
        try {
            return iterable.getIdOrThrow(value);
        } catch (IllegalArgumentException original) {
            try {
                if (!(value instanceof Holder.Reference<?> reference)) {
                    throw original;
                }

                ResourceKey<?> key;
                try {
                    key = reference.key();
                } catch (Exception ignored) {
                    key = null;
                }
                if (key == null) {
                    throw original;
                }

                Holder.Reference<?> resolved = tryResolveReference(iterable, key);
                if (resolved == null) {
                    throw original;
                }

                return iterable.getIdOrThrow(resolved);
            } catch (Throwable t) {
                AdvancedMobsMod.LOGGER.error("[RegistryEntryEncodingFixMixin] Raw id resolution failed", t);
                try {
                    return iterable.getIdOrThrow(value);
                } catch (Throwable ignored) {
                    return 0;
                }
            }
        } catch (Throwable t) {
            AdvancedMobsMod.LOGGER.error("[RegistryEntryEncodingFixMixin] Raw id resolution failed", t);
            return 0;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Holder.Reference<?> tryResolveReference(IdMap iterable, ResourceKey<?> key) {
        if (iterable == null || key == null) {
            return null;
        }

        // The concrete iterable used by the play-phase buffer is typically a registry wrapper
        // that exposes a "getEntry(ResourceKey)" or similar method returning Optional.
        // Use reflection to stay resilient to minor mapping / API differences.
        for (String methodName : new String[] {"getEntry", "getOptional", "get", "getOrEmpty"}) {
            try {
                Method method = iterable.getClass().getMethod(methodName, ResourceKey.class);
                if (!Optional.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                Object result = method.invoke(iterable, key);
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

        // Fallback: the iterable in the error is often a Registry anonymous inner class (Registry$1)
        // that captures the outer Registry instance. Pull it out via reflection and resolve there.
        try {
            Registry<?> outerRegistry = tryGetOuterRegistry(iterable);
            if (outerRegistry != null) {
                Optional<? extends Holder.Reference<?>> resolved = outerRegistry.get((ResourceKey) key);
                return resolved.orElse(null);
            }
        } catch (Exception ignored) {
            // fall through
        }

        return null;
    }

    private static Registry<?> tryGetOuterRegistry(IdMap iterable) {
        try {
            for (Field field : iterable.getClass().getDeclaredFields()) {
                if (!Registry.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(iterable);
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
