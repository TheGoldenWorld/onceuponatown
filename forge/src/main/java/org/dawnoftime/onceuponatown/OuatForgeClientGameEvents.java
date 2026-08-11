package org.dawnoftime.onceuponatown;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.dawnoftime.onceuponatown.client.renderer.PingRenderer;

// Tick and world render events live on the FORGE bus; kept separate from OuatForgeClient (MOD bus).
@Mod.EventBusSubscriber(modid = Constants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class OuatForgeClientGameEvents {

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            PingRenderer.tick();
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
            Vec3 cam = camera.getPosition();
            MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
            PingRenderer.render(
                event.getPoseStack(),
                bufferSource,
                event.getPartialTick(),
                Minecraft.getInstance().level.getGameTime(),
                cam.x, cam.y, cam.z
            );
            PingRenderer.flush(bufferSource);
        }
    }

    @SubscribeEvent
    public static void onClientDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        PingRenderer.clearAll();
    }
}
