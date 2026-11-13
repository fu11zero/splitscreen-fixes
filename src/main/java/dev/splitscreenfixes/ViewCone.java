package dev.splitscreenfixes;

// The view cone (the darkening outside the character's field of view) is drawn by
// VisibilityPolygon2$Drawer.renderToScreen with media/shaders/visibilityBlur.frag. The draw goes
// into the shared offscreen buffer with the viewport at the player's screen, so gl_FragCoord
// includes IsoCamera.getScreenLeft/Top(player), passed as displayOrigin. Two lines of the shader
// get the player's part of the window wrong:
//
// 1. The blur samples don't subtract displayOrigin:
//        ivec2 pixel = ivec2(gl_FragCoord.xy / screenSize * texSize);
//    For every player but 0 the samples land outside the polygon texture (clamped to its edge),
//    the shadow alpha is 0 and there is no cone at all.
//
// 2. The depth lookup divides by displaySize:
//        vec2 screenUV = (gl_FragCoord.xy - displayOrigin.xy) / displaySize;
//    renderToScreen sets displaySize to the offscreen texture size split like vanilla's window
//    (osWidth / 2 with 2+ players, osHeight / 2 with 3+), meant to equal the player's screen
//    scaled by the polygon texture's used part (texSize / TextureSize: the texture is a power of
//    two). That holds only when the player has exactly a half/quarter of the window; with one
//    player per monitor and monitors of different sizes the depth is read from the wrong place
//    and the shadows shift. Compute it from the player's screen directly; for vanilla's layouts
//    the value is the same.
//
// Patch: after vanilla ShaderUnit.loadShaderFile reads visibilityBlur.frag, replace both lines.
// With one player displayOrigin is 0, 0 and nothing changes.
public final class ViewCone {
    private static final String SHADER = "visibilityBlur.frag";
    private static final String[][] FIXES = {
        {"ivec2(gl_FragCoord.xy / screenSize * texSize)",
         "ivec2((gl_FragCoord.xy - displayOrigin.xy) / screenSize * texSize)"},
        {"(gl_FragCoord.xy - displayOrigin.xy) / displaySize;",
         "(gl_FragCoord.xy - displayOrigin.xy) / screenSize * (texSize / TextureSize);"},
    };

    private ViewCone() {}

    public static String afterLoadShaderFile(String fileName, String code) {
        if (code == null || fileName == null || !fileName.endsWith(SHADER)) {
            return code;
        }
        for (String[] fix : FIXES) {
            if (code.contains(fix[0])) {
                code = code.replace(fix[0], fix[1]);
            } else if (!code.contains(fix[1])) {
                Log.warn(SHADER + ": line not found, left vanilla: " + fix[0]);
            }
        }
        return code;
    }
}
