package dev.splitscreenfixes;

// The view cone (the darkening outside the character's field of view) is drawn by
// VisibilityPolygon2$Drawer.renderToScreen with media/shaders/visibilityBlur.frag. The draw goes
// into the shared offscreen buffer with the viewport at the player's screen, so gl_FragCoord
// includes IsoCamera.getScreenLeft/Top(player), passed as displayOrigin. The shader subtracts
// displayOrigin for screenUV, but not for the blur samples:
//     ivec2 pixel = ivec2(gl_FragCoord.xy / screenSize * texSize);
// For every player but 0 the samples land outside the polygon texture (clamped to its edge),
// the shadow alpha is 0 and there is no cone at all.
//
// Patch: after vanilla ShaderUnit.loadShaderFile reads visibilityBlur.frag, subtract
// displayOrigin there too. With one player displayOrigin is 0, 0 and nothing changes.
public final class ViewCone {
    private static final String SHADER = "visibilityBlur.frag";
    private static final String VANILLA = "ivec2(gl_FragCoord.xy / screenSize * texSize)";
    private static final String FIXED = "ivec2((gl_FragCoord.xy - displayOrigin.xy) / screenSize * texSize)";

    private ViewCone() {}

    public static String afterLoadShaderFile(String fileName, String code) {
        if (code == null || fileName == null || !fileName.endsWith(SHADER)) {
            return code;
        }
        if (!code.contains(VANILLA)) {
            if (!code.contains(FIXED)) {
                Log.warn(SHADER + ": blur sample line not found, view cone left vanilla");
            }
            return code;
        }
        Log.info(SHADER + ": view cone samples relative to the player's screen");
        return code.replace(VANILLA, FIXED);
    }
}
