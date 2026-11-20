package mod.advancedmobs.client;

import com.mojang.blaze3d.vertex.PoseStack;
import mod.advancedmobs.entity.MobWarlordEntity;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.monster.witch.WitchModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.state.WitchRenderState;
import net.minecraft.resources.Identifier;

/**
 * Custom renderer for the Mob Warlord that uses the witch model.
 * This renders the boss as a giant witch without the particle crashes.
 *
 * 26.2 rendering note: renderers are typed by render state now, so this
 * uses the vanilla WitchRenderState with the witch model, scaled 2x,
 * same as the old renderer.
 */
public class MobWarlordRenderer extends MobRenderer<MobWarlordEntity, WitchRenderState, WitchModel> {
    
    private static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/entity/witch.png");
    
    public MobWarlordRenderer(EntityRendererProvider.Context context) {
        super(context, new WitchModel(context.bakeLayer(ModelLayers.WITCH)), 0.5f * 2.0f); // Shadow radius 2x
    }
    
    @Override
    public WitchRenderState createRenderState() {
        return new WitchRenderState();
    }

    @Override
    public Identifier getTextureLocation(WitchRenderState state) {
        return TEXTURE;
    }
    
    @Override
    protected void scale(WitchRenderState state, PoseStack matrices) {
        // Scale the model to 2x size
        matrices.scale(2.0f, 2.0f, 2.0f);
        super.scale(state, matrices);
    }
}
