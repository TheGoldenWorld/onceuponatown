package org.dawnoftime.onceuponatown.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

public class PingRenderer {

    private static final ResourceLocation BEAM_TEXTURE =
        new ResourceLocation("textures/entity/beacon_beam.png");

    private static final int PING_DURATION_TICKS = 600;

    private static final List<BlockPos> positions = new ArrayList<>();
    private static final List<Integer> ticksLeft = new ArrayList<>();

    public static void addPing(BlockPos pos) {
        int idx = positions.indexOf(pos);
        if (idx >= 0) {
            ticksLeft.set(idx, PING_DURATION_TICKS);
        } else {
            positions.add(pos);
            ticksLeft.add(PING_DURATION_TICKS);
        }
    }

    public static void tick() {
        for (int i = ticksLeft.size() - 1; i >= 0; i--) {
            int remaining = ticksLeft.get(i) - 1;
            if (remaining <= 0) {
                positions.remove(i);
                ticksLeft.remove(i);
            } else {
                ticksLeft.set(i, remaining);
            }
        }
    }

    public static void render(PoseStack poseStack, MultiBufferSource bufferSource,
                              float partialTick, long gameTime, double camX, double camY, double camZ) {
        if (positions.isEmpty()) return;
        for (BlockPos pos : positions) {
            poseStack.pushPose();
            poseStack.translate(pos.getX() + 0.5 - camX, pos.getY() - camY, pos.getZ() + 0.5 - camZ);
            renderBeamLayer(poseStack, bufferSource, BEAM_TEXTURE, partialTick, gameTime,
                256, 1.0f, 1.0f, 1.0f, 1.0f, 0.2f);
            renderBeamLayer(poseStack, bufferSource, BEAM_TEXTURE, partialTick, gameTime,
                256, 1.0f, 1.0f, 1.0f, 0.125f, 0.25f);
            poseStack.popPose();
        }
    }

    // Flush only the beam render types to avoid interfering with other pending geometry.
    public static void flush(MultiBufferSource.BufferSource bufferSource) {
        bufferSource.endBatch(RenderType.beaconBeam(BEAM_TEXTURE, false));
        bufferSource.endBatch(RenderType.beaconBeam(BEAM_TEXTURE, true));
    }

    private static void renderBeamLayer(PoseStack poseStack, MultiBufferSource bufferSource,
                                        ResourceLocation texture, float partialTick, long gameTime,
                                        int height, float r, float g, float b, float alpha, float radius) {
        float scroll = (float) Math.floorMod(gameTime, 40) + partialTick;
        // Negative scroll direction so texture appears to travel upward
        float uvFrac = Mth.frac(-scroll * 0.2f - (float) Mth.floor(-scroll * 0.1f));
        float v0 = -1.0f + uvFrac;
        float v1 = (float) height * (0.5f / radius) + v0;

        VertexConsumer consumer = bufferSource.getBuffer(RenderType.beaconBeam(texture, alpha < 1.0f));

        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(scroll * 2.25f - 45.0f));

        Matrix4f pose = poseStack.last().pose();
        Matrix3f normal = poseStack.last().normal();

        // Corners of the square tube in XZ, matching vanilla's diamond orientation
        float[] cx = {  0.0f,  radius,  0.0f, -radius };
        float[] cz = { radius,  0.0f,  -radius,  0.0f  };

        for (int f = 0; f < 4; f++) {
            int next = (f + 1) % 4;
            consumer.vertex(pose, cx[f],    0,      cz[f])    .color(r, g, b, alpha).uv(0, v0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(15728880).normal(normal, 0, 1, 0).endVertex();
            consumer.vertex(pose, cx[f],    height, cz[f])    .color(r, g, b, alpha).uv(0, v1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(15728880).normal(normal, 0, 1, 0).endVertex();
            consumer.vertex(pose, cx[next], height, cz[next]) .color(r, g, b, alpha).uv(1, v1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(15728880).normal(normal, 0, 1, 0).endVertex();
            consumer.vertex(pose, cx[next], 0,      cz[next]) .color(r, g, b, alpha).uv(1, v0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(15728880).normal(normal, 0, 1, 0).endVertex();
        }

        poseStack.popPose();
    }

    public static void clearAll() {
        positions.clear();
        ticksLeft.clear();
    }
}
