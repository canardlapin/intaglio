package com.sun.glass.ui.monocle;

/** A simulated 2x screen, using the real Monocle window/event/software-rendering stack. */
public final class Scale2HeadlessPlatformFactory extends NativePlatformFactory {
    @Override protected boolean matches() { return true; }
    @Override protected int getMajorVersion() { return 1; }
    @Override protected int getMinorVersion() { return 0; }
    @Override protected NativePlatform createNativePlatform() {
        return new HeadlessPlatform() {
            @Override protected NativeScreen createScreen() {
                return new HeadlessScreen() {
                    @Override public float getScale() { return 2.0f; }
                };
            }
        };
    }
}
