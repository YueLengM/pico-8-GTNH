package com.yuelengm.pico8gtnh;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;

/**
 * Java host for PICO-R's JavaScript-free WebAssembly interface.
 */
public final class PicoRRuntime {

    private static final String WASM_RESOURCE = "/assets/pico8gtnh/pico-r.wasm";
    public static final int SCREEN_WIDTH = 128;
    public static final int SCREEN_HEIGHT = 128;
    private static final int PIXEL_BUFFER_SIZE = SCREEN_WIDTH * SCREEN_HEIGHT * Integer.BYTES;

    private final Instance instance;
    private final Memory memory;
    private final ExportFunction alloc;
    private final ExportFunction free;
    private final ExportFunction init;
    private final ExportFunction update;
    private final ExportFunction setButtons;
    private final ExportFunction getPixelBuffer;
    private final ExportFunction getFps;
    private boolean cartLoaded;

    private PicoRRuntime(Instance instance) {
        this.instance = instance;
        this.memory = instance.memory();
        this.alloc = instance.export("web_alloc");
        this.free = instance.export("web_free");
        this.init = instance.export("web_init");
        this.update = instance.export("web_update");
        this.setButtons = instance.export("web_set_buttons");
        this.getPixelBuffer = instance.export("web_get_pixel_buffer");
        this.getFps = instance.export("web_get_fps");
    }

    /**
     * Loads a PICO-R wasm module. The caller owns and closes the input stream.
     */
    public static PicoRRuntime load(InputStream wasm) throws IOException {
        WasmModule module = Parser.parse(wasm);
        return new PicoRRuntime(
            Instance.builder(module)
                .build());
    }

    /**
     * Loads the PICO-R module bundled with this mod.
     */
    public static PicoRRuntime loadBundled() throws IOException {
        InputStream wasm = PicoRRuntime.class.getResourceAsStream(WASM_RESOURCE);
        if (wasm == null) {
            throw new IOException("Bundled PICO-R module is missing: " + WASM_RESOURCE);
        }
        try {
            return load(wasm);
        } finally {
            wasm.close();
        }
    }

    /**
     * Loads a .p8 or .p8.png cart into the emulator.
     */
    public void loadCart(byte[] cart) {
        if (cart == null || cart.length == 0) {
            throw new IllegalArgumentException("Cart data must not be empty");
        }

        int pointer = (int) alloc.apply(cart.length)[0];
        try {
            memory.write(pointer, cart);
            long result = init.apply(pointer, cart.length)[0];
            if (result != 0) {
                throw new IllegalArgumentException("PICO-R could not load the cart (error " + result + ")");
            }
            cartLoaded = true;
        } finally {
            free.apply(pointer, cart.length);
        }
    }

    /**
     * Sets the PICO-8 button bitmask for player 0 or 1.
     */
    public void setButtons(int player, int buttonBits) {
        ensureCartLoaded();
        if (player < 0 || player > 1) {
            throw new IllegalArgumentException("Player must be 0 or 1");
        }
        setButtons.apply(player, buttonBits);
    }

    /**
     * Advances the emulator by one frame.
     */
    public void update() {
        ensureCartLoaded();
        update.apply();
    }

    /** Returns the frame rate selected by the loaded cart, either 30 or 60. */
    public int getFramesPerSecond() {
        ensureCartLoaded();
        return (int) getFps.apply()[0];
    }

    /**
     * Returns the current 128x128 frame as ARGB pixels.
     */
    public int[] getPixels() {
        ensureCartLoaded();
        int pointer = (int) getPixelBuffer.apply()[0];
        byte[] bytes = memory.readBytes(pointer, PIXEL_BUFFER_SIZE);
        int[] pixels = new int[SCREEN_WIDTH * SCREEN_HEIGHT];
        ByteBuffer.wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asIntBuffer()
            .get(pixels);
        return pixels;
    }

    private void ensureCartLoaded() {
        if (!cartLoaded) {
            throw new IllegalStateException("Load a PICO-8 cart before running frames");
        }
    }
}
