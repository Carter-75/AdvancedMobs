package mod.advancedmobs.client;

import mod.advancedmobs.entity.MobWarlordEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.monster.witch.WitchModel;
import net.minecraft.resources.Identifier;

/**
 * Custom renderer for the Mob Warlord that uses the witch model.
 * This renders the boss as a giant witch without the particle crashes.
 */
public class MobWarlordRenderer extends MobRenderer<MobWarlordEntity, WitchModel<MobWarlordEntity>> {
    
    private static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/entity/witch.png");
    
    public MobWarlordRenderer(EntityRendererProvider.Context context) {
        super(context, new WitchModel<>(context.getPart(ModelLayers.WITCH)), 0.5f * 2.0f); // Shadow radius 2x
    }
    
    @Override
    public Identifier getTexture(MobWarlordEntity entity) {
        return TEXTURE;
    }
    
    @Override
    protected void scale(MobWarlordEntity entity, com.mojang.blaze3d.vertex.PoseStack matrices, float amount) {
        // Scale the model to 2x size
        matrices.scale(2.0f, 2.0f, 2.0f);
        super.scale(entity, matrices, amount);
    }
}

