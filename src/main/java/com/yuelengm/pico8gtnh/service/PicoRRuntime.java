package com.yuelengm.pico8gtnh.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Collections;

import io.github.kawamuray.wasmtime.Engine;
import io.github.kawamuray.wasmtime.Func;
import io.github.kawamuray.wasmtime.Instance;
import io.github.kawamuray.wasmtime.Memory;
import io.github.kawamuray.wasmtime.Module;
import io.github.kawamuray.wasmtime.Store;
import io.github.kawamuray.wasmtime.Val;

/** Java host for PICO-R's WebAssembly interface. */
public final class PicoRRuntime implements AutoCloseable {

    private static final String WASM_RESOURCE = "/assets/pico8/pico-r.wasm";
    public static final int SCREEN_WIDTH = 128;
    public static final int SCREEN_HEIGHT = 128;
    public static final int AUDIO_SAMPLE_RATE = 22050;
    private static final int MAX_AUDIO_SAMPLES = 4096;

    private final Engine engine;
    private final Store<Void> store;
    private final Module module;
    private final Instance instance;
    private final Memory memory;
    private final Func alloc;
    private final Func free;
    private final Func init;
    private final Func update;
    private final Func setButtons;
    private final Func getPixelBuffer;
    private final Func generateAudio;
    private final Func getFps;
    private final Func saveStateFunction;
    private final Func getSavePointer;
    private final Func freeSaveState;
    private final Func loadStateFunction;
    private boolean cartLoaded;

    private PicoRRuntime(Engine engine, Store<Void> store, Module module, Instance instance) {
        this.engine = engine;
        this.store = store;
        this.module = module;
        this.instance = instance;
        this.memory = instance.getMemory(store, "memory")
            .orElseThrow(() -> new IllegalStateException("PICO-R WASM memory export is missing"));
        this.alloc = getFunction("web_alloc");
        this.free = getFunction("web_free");
        this.init = getFunction("web_init");
        this.update = getFunction("web_update");
        this.setButtons = getFunction("web_set_buttons");
        this.getPixelBuffer = getFunction("web_get_pixel_buffer");
        this.generateAudio = getFunction("web_generate_audio");
        this.getFps = getFunction("web_get_fps");
        this.saveStateFunction = getFunction("web_save_state");
        this.getSavePointer = getFunction("web_get_save_ptr");
        this.freeSaveState = getFunction("web_free_save");
        this.loadStateFunction = getFunction("web_load_state");
    }

    /** Loads a PICO-R WASM module. The caller owns and closes the input stream. */
    public static PicoRRuntime load(InputStream wasm) throws IOException {
        Engine engine = new Engine();
        Store<Void> store = new Store<>(null, engine, null);
        Module module = null;
        Instance instance = null;
        try {
            module = Module.fromBinary(engine, readAllBytes(wasm));
            instance = new Instance(store, module, Collections.emptyList());
            return new PicoRRuntime(engine, store, module, instance);
        } catch (RuntimeException | LinkageError exception) {
            if (instance != null) {
                instance.dispose();
            }
            if (module != null) {
                module.dispose();
            }
            store.dispose();
            engine.dispose();
            throw exception;
        }
    }

    /** Loads the PICO-R module bundled with this mod. */
    public static PicoRRuntime loadBundled() throws IOException {
        InputStream wasm = PicoRRuntime.class.getResourceAsStream(WASM_RESOURCE);
        try (wasm) {
            if (wasm == null) {
                throw new IOException("Bundled PICO-R module is missing: " + WASM_RESOURCE);
            }
            return load(wasm);
        }
    }

    /** Loads a .p8 or .p8.png cart into the emulator. */
    public void loadCart(byte[] cart) {
        if (cart == null || cart.length == 0) {
            throw new IllegalArgumentException("Cart data must not be empty");
        }

        int pointer = callI32(alloc, Val.fromI32(cart.length));
        try {
            ByteBuffer buffer = memoryBuffer();
            buffer.position(pointer);
            buffer.put(cart);
            int result = callI32(init, Val.fromI32(pointer), Val.fromI32(cart.length));
            if (result != 0) {
                throw new IllegalArgumentException("PICO-R could not load the cart (error " + result + ")");
            }
            cartLoaded = true;
        } finally {
            free.call(store, Val.fromI32(pointer), Val.fromI32(cart.length));
        }
    }

    /** Sets the PICO-8 button bitmask for player 0 or 1. */
    public void setButtons(int player, int buttonBits) {
        ensureCartLoaded();
        if (player < 0 || player > 1) {
            throw new IllegalArgumentException("Player must be 0 or 1");
        }
        setButtons.call(store, Val.fromI32(player), Val.fromI32(buttonBits));
    }

    /** Advances the emulator by one frame. */
    public void update() {
        ensureCartLoaded();
        update.call(store);
    }

    /** Returns the frame rate selected by the loaded cart, either 30 or 60. */
    public int getFramesPerSecond() {
        ensureCartLoaded();
        return callI32(getFps);
    }

    /** Returns the current 128x128 frame as ARGB pixels. */
    public void copyPixelsTo(int[] target) {
        ensureCartLoaded();
        if (target.length < SCREEN_WIDTH * SCREEN_HEIGHT) {
            throw new IllegalArgumentException("Pixel target is too small");
        }
        int pointer = callI32(getPixelBuffer);
        ByteBuffer pixels = memoryBuffer();
        pixels.position(pointer);
        IntBuffer frame = pixels.slice()
            .order(ByteOrder.LITTLE_ENDIAN)
            .asIntBuffer();
        frame.get(target, 0, SCREEN_WIDTH * SCREEN_HEIGHT);
    }

    /** Generates mono PCM audio samples and converts them to signed 16-bit little-endian data. */
    public byte[] generateAudio(int sampleCount, float volume) {
        ensureCartLoaded();
        if (sampleCount < 0 || sampleCount > MAX_AUDIO_SAMPLES) {
            throw new IllegalArgumentException("Audio sample count must be between 0 and " + MAX_AUDIO_SAMPLES);
        }
        if (sampleCount == 0) {
            return new byte[0];
        }

        int pointer = callI32(generateAudio, Val.fromI32(sampleCount));
        ByteBuffer samples = memoryBuffer();
        samples.position(pointer);
        FloatBuffer floatSamples = samples.slice()
            .order(ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer();
        byte[] pcm = new byte[sampleCount * Short.BYTES];
        ByteBuffer output = ByteBuffer.wrap(pcm)
            .order(ByteOrder.LITTLE_ENDIAN);
        float safeVolume = Math.max(0.0F, Math.min(1.0F, volume));
        for (int i = 0; i < sampleCount; i++) {
            float sample = floatSamples.get() * safeVolume;
            int value = Math.round(sample * Short.MAX_VALUE);
            value = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
            output.putShort((short) value);
        }
        return pcm;
    }

    /** Captures the current emulator state using PICO-R's serialized save buffer. */
    public byte[] saveState() {
        ensureCartLoaded();
        int length = callI32(saveStateFunction);
        if (length <= 0) {
            return null;
        }

        try {
            int pointer = callI32(getSavePointer);
            if (pointer <= 0) {
                return null;
            }
            ByteBuffer source = memoryBuffer();
            if ((long) pointer + length > source.capacity()) {
                throw new IllegalStateException("PICO-R save buffer is outside WASM memory");
            }
            byte[] state = new byte[length];
            source.position(pointer);
            source.get(state);
            return state;
        } finally {
            freeSaveState.call(store);
        }
    }

    /** Restores a state previously returned by {@link #saveState()}. */
    public void loadState(byte[] state) {
        ensureCartLoaded();
        if (state == null || state.length == 0) {
            throw new IllegalArgumentException("Saved state must not be empty");
        }

        int pointer = callI32(alloc, Val.fromI32(state.length));
        if (pointer <= 0) {
            throw new IllegalStateException("Could not allocate memory for the PICO-R saved state");
        }
        try {
            ByteBuffer target = memoryBuffer();
            if ((long) pointer + state.length > target.capacity()) {
                throw new IllegalStateException("PICO-R load buffer is outside WASM memory");
            }
            target.position(pointer);
            target.put(state);
            loadStateFunction.call(store, Val.fromI32(pointer), Val.fromI32(state.length));
        } finally {
            free.call(store, Val.fromI32(pointer), Val.fromI32(state.length));
        }
    }

    public int getWasmMemoryPages() {
        return memory.size(store);
    }

    @Override
    public void close() {
        generateAudio.dispose();
        getPixelBuffer.dispose();
        setButtons.dispose();
        getFps.dispose();
        loadStateFunction.dispose();
        freeSaveState.dispose();
        getSavePointer.dispose();
        saveStateFunction.dispose();
        update.dispose();
        init.dispose();
        free.dispose();
        alloc.dispose();
        memory.dispose();
        instance.dispose();
        module.dispose();
        store.dispose();
        engine.dispose();
    }

    private Func getFunction(String name) {
        return instance.getFunc(store, name)
            .orElseThrow(() -> new IllegalStateException("PICO-R WASM export is missing: " + name));
    }

    private int callI32(Func function, Val... arguments) {
        return function.call(store, arguments)[0].i32();
    }

    private ByteBuffer memoryBuffer() {
        return memory.buffer(store)
            .order(ByteOrder.LITTLE_ENDIAN);
    }

    private void ensureCartLoaded() {
        if (!cartLoaded) {
            throw new IllegalStateException("Load a PICO-8 cart before running frames");
        }
    }

    private static byte[] readAllBytes(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
