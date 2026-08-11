package org.dawnoftime.onceuponatown.client.gui.widgets;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class SocialsWidget extends DraggableWidget {

    private static final int CARD_W      = 90;
    private static final int CARD_PAD    = 6;
    private static final int GAP         = 6;
    private static final int OUTER_PAD   = 4;
    private static final int BTN_H       = 12;
    private static final int DESC_LINE_H = 10;
    private static final int TEXT_W      = CARD_W - CARD_PAD * 2;

    private record SocialEntry(String title, String description, String fullUrl, int brandColor) {}

    private static final List<SocialEntry> SOCIALS = List.of(
        new SocialEntry(
            "Discord",
            "Chat with the community, share your villages and get help",
            "https://discord.gg/9p9HKWmJWG",
            0xFF5865F2),
        new SocialEntry(
            "Patreon",
            "Support the project and help new content come to life",
            "https://www.patreon.com/cw/dawnoftimemod",
            0xFFFF424D),
        new SocialEntry(
            "YouTube",
            "Watch dev logs, tutorials and village showcases",
            "https://www.youtube.com/@dawnoftime8964",
            0xFFFF0000),
        new SocialEntry(
            "GitHub",
            "Report bugs, suggest features or explore the source code",
            "https://github.com/Dawn-of-Time-Project",
            0xFFCCCCCC)
    );

    private final int[][] btnBounds = new int[SOCIALS.size()][4];

    public SocialsWidget(int x, int y, int freeZoneMaxX, int screenH) {
        super(x, y, computeWidgetW(), computeWidgetH(), freeZoneMaxX, screenH);
    }

    // 2 columns
    public static int computeWidgetW() {
        return OUTER_PAD * 2 + 2 * CARD_W + GAP;
    }

    // 2 rows
    public static int computeWidgetH() {
        Font font = Minecraft.getInstance().font;
        int maxLines = SOCIALS.stream()
            .mapToInt(s -> splitLines(font, s.description(), TEXT_W).size())
            .max().orElse(3);
        int cardH = CARD_PAD + 9 + 4 + maxLines * DESC_LINE_H + 4 + BTN_H + CARD_PAD;
        return TITLE_BAR_H + OUTER_PAD + 2 * cardH + GAP + OUTER_PAD;
    }

    @Override
    protected String getTitle() { return "Socials"; }

    @Override
    protected void renderContent(GuiGraphics g, int cx, int cy, int cw, int ch, int mx, int my, float delta) {
        Font font = Minecraft.getInstance().font;
        g.fill(cx, cy, cx + cw, cy + ch, 0xF5111111);

        int maxLines = SOCIALS.stream()
            .mapToInt(s -> splitLines(font, s.description(), TEXT_W).size())
            .max().orElse(3);
        int cardH = CARD_PAD + 9 + 4 + maxLines * DESC_LINE_H + 4 + BTN_H + CARD_PAD;

        // 2x2 grid: index 0,1 = top row; 2,3 = bottom row
        int[] colX = { cx + OUTER_PAD, cx + OUTER_PAD + CARD_W + GAP };
        int[] rowY = { cy + OUTER_PAD, cy + OUTER_PAD + cardH + GAP };

        for (int i = 0; i < SOCIALS.size(); i++) {
            SocialEntry s = SOCIALS.get(i);
            int cardX = colX[i % 2];
            int cardY = rowY[i / 2];
            boolean hover = mx >= cardX && mx < cardX + CARD_W && my >= cardY && my < cardY + cardH;

            g.fill(cardX, cardY, cardX + CARD_W, cardY + cardH, 0xFF1A1A1A);
            if (hover) {
                int bc = s.brandColor();
                g.fill(cardX,              cardY,             cardX + CARD_W, cardY + 1,             bc);
                g.fill(cardX,              cardY + cardH - 1, cardX + CARD_W, cardY + cardH,          bc);
                g.fill(cardX,              cardY,             cardX + 1,      cardY + cardH,          bc);
                g.fill(cardX + CARD_W - 1, cardY,             cardX + CARD_W, cardY + cardH,          bc);
            }

            int y = cardY + CARD_PAD;

            // Title -- brand color on hover
            g.drawString(font, s.title(),
                cardX + (CARD_W - font.width(s.title())) / 2, y,
                hover ? s.brandColor() : 0xFFEEEEEE, false);
            y += 9 + 4;

            // Description word-wrapped, centered
            List<String> lines = splitLines(font, s.description(), TEXT_W);
            for (String line : lines) {
                g.drawString(font, line,
                    cardX + (CARD_W - font.width(line)) / 2, y,
                    0xFF777777, false);
                y += DESC_LINE_H;
            }
            y += 4;

            // Button -- neutral highlight on hover, no brand color
            int btnX = cardX + CARD_PAD;
            int btnW = CARD_W - CARD_PAD * 2;
            int btnY = cardY + cardH - CARD_PAD - BTN_H;
            boolean btnHover = hover && mx >= btnX && mx < btnX + btnW && my >= btnY && my < btnY + BTN_H;

            g.fill(btnX, btnY, btnX + btnW, btnY + BTN_H, btnHover ? 0xFF2E2E2E : 0xFF1E1E1E);
            g.fill(btnX,           btnY,             btnX + btnW, btnY + 1,        btnHover ? 0xFF555555 : 0xFF333333);
            g.fill(btnX,           btnY + BTN_H - 1, btnX + btnW, btnY + BTN_H,    btnHover ? 0xFF555555 : 0xFF333333);
            g.fill(btnX,           btnY,             btnX + 1,    btnY + BTN_H,    btnHover ? 0xFF555555 : 0xFF333333);
            g.fill(btnX + btnW - 1, btnY,            btnX + btnW, btnY + BTN_H,    btnHover ? 0xFF555555 : 0xFF333333);

            String label = "Open " + s.title();
            g.drawString(font, label,
                btnX + (btnW - font.width(label)) / 2, btnY + 2,
                btnHover ? 0xFFBBBBBB : 0xFF888888, false);

            btnBounds[i] = new int[]{ btnX, btnY, btnW, BTN_H };
        }
    }

    @Override
    protected boolean contentMouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return isMouseOver(mouseX, mouseY);
        for (int i = 0; i < SOCIALS.size(); i++) {
            int[] b = btnBounds[i];
            if (mouseX >= b[0] && mouseX < b[0] + b[2] && mouseY >= b[1] && mouseY < b[1] + b[3]) {
                try {
                    Util.getPlatform().openUri(new URI(SOCIALS.get(i).fullUrl()));
                } catch (Exception ignored) {}
                return true;
            }
        }
        return isMouseOver(mouseX, mouseY);
    }

    private static List<String> splitLines(Font font, String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (font.width(candidate) <= maxWidth) {
                current = new StringBuilder(candidate);
            } else {
                if (!current.isEmpty()) lines.add(current.toString());
                current = new StringBuilder(word);
            }
        }
        if (!current.isEmpty()) lines.add(current.toString());
        return lines;
    }
}
