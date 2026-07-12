package me.mvk.mimicNPC.voice;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import me.mvk.mimicNPC.MimicNPCPlugin;

import java.util.UUID;


public class NpcVoicechatPlugin implements VoicechatPlugin {

    private final MimicNPCPlugin ownerPlugin;

    public NpcVoicechatPlugin(MimicNPCPlugin ownerPlugin) {
        this.ownerPlugin = ownerPlugin;
    }

    @Override
    public String getPluginId() {
        return "randomnpc-voice";
    }

    @Override
    public void initialize(VoicechatApi api) {
        ownerPlugin.getVoiceCaptureManager().setApi((VoicechatServerApi) api);
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicPacket);
    }

    private void onMicPacket(MicrophonePacketEvent event) {
        UUID playerId = event.getSenderConnection().getPlayer().getUuid();
        if (!ownerPlugin.getVoiceCaptureManager().isTracked(playerId)) return;
        ownerPlugin.getVoiceCaptureManager().onMicPacket(playerId, event.getPacket().getOpusEncodedData());
    }
}