package com.pixelreel.networking;

import java.util.function.Consumer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Hands clientbound packets to client-only code without loading those classes on a dedicated server.
 */
public final class ClientPacketDispatch {
	private static volatile Consumer<CustomPacketPayload> handler = payload -> {
	};

	private ClientPacketDispatch() {
	}

	public static void setHandler(Consumer<CustomPacketPayload> newHandler) {
		handler = newHandler;
	}

	public static void handle(CustomPacketPayload payload) {
		handler.accept(payload);
	}
}
