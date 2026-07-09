package org.dawnoftime.onceuponatown.client.model.layer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import org.dawnoftime.onceuponatown.Ouat;
import org.dawnoftime.onceuponatown.client.model.NpcModel;
import org.dawnoftime.onceuponatown.client.renderer.NpcRenderer;
import org.dawnoftime.onceuponatown.entity.Npc;

import java.util.Map;

public class NpcClothesLayer<T extends Npc, M extends NpcModel<T>> extends RenderLayer<T, M> {
    private static final Map<String, ResourceLocation> CLOTHES_BY_JOB = Map.of(
        "builder",    new ResourceLocation(Ouat.MOD_ID, "textures/entity/npc/builder_clothes.png"),
        "lumberjack", new ResourceLocation(Ouat.MOD_ID, "textures/entity/npc/lumberjack_clothes.png")
    );

    @SuppressWarnings("unchecked")
    public NpcClothesLayer(NpcRenderer renderer) {
        super((RenderLayerParent<T, M>) renderer);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, T npc,
                        float limbSwing, float limbSwingAmount, float partialTick,
                        float ageInTicks, float netHeadYaw, float headPitch) {
        if (npc.isInvisible()) return;
        ResourceLocation texture = CLOTHES_BY_JOB.get(npc.getJobId());
        if (texture == null) return;
        renderColoredCutoutModel(getParentModel(), texture, poseStack, buffer, packedLight, npc, 1.0F, 1.0F, 1.0F);
    }
}
