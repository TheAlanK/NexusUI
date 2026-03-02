package com.nexusui.overlay;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.listeners.CampaignInputListener;
import com.fs.starfarer.api.campaign.listeners.CampaignUIRenderingListener;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import org.lwjgl.opengl.GL11;

import javax.swing.*;
import java.awt.Color;
import java.util.List;

import org.apache.log4j.Logger;

/**
 * NexusOverlay - Floating draggable button rendered on the campaign screen.
 *
 * Renders a glowing "N" button that can be dragged around the screen.
 * When clicked (without dragging), opens the NexusUI in-game dashboard frame.
 * Renders ABOVE all game UI, including tooltips.
 */
public class NexusOverlay implements CampaignUIRenderingListener, CampaignInputListener {

    private static final Logger log = Logger.getLogger(NexusOverlay.class);

    private int port;
    private float pulseTimer = 0f;
    private boolean hovered = false;

    // Button position and size
    private float btnX = -1, btnY = -1;
    private static final float BTN_WIDTH = 36f;
    private static final float BTN_HEIGHT = 36f;
    private static final float BTN_MARGIN = 12f;

    // Drag state
    private boolean dragging = false;
    private float dragOffsetX, dragOffsetY;
    private float dragStartX, dragStartY;
    private static final float DRAG_THRESHOLD = 5f;
    private boolean mouseDown = false;

    // Colors
    private static final Color COLOR_BG = new Color(15, 25, 40, 200);
    private static final Color COLOR_BORDER = new Color(80, 180, 255, 180);
    private static final Color COLOR_GLOW = new Color(80, 180, 255, 60);
    private static final Color COLOR_HOVER_BG = new Color(30, 60, 100, 220);
    private static final Color COLOR_ICON = new Color(120, 210, 255);
    private static final Color COLOR_CONNECTED = new Color(80, 255, 120);

    private int mouseX, mouseY;

    public NexusOverlay(int port) {
        this.port = port;
    }

    // ========================================================================
    // CampaignUIRenderingListener - Render the floating button
    // ========================================================================

    public void renderInUICoordsBelowUI(ViewportAPI viewport) {}
    public void renderInUICoordsAboveUIBelowTooltips(ViewportAPI viewport) {}

    public void renderInUICoordsAboveUIAndTooltips(ViewportAPI viewport) {
        float screenW = Global.getSettings().getScreenWidth();
        float screenH = Global.getSettings().getScreenHeight();

        // Default position: top-right corner
        if (btnX < 0) {
            btnX = screenW - BTN_WIDTH - BTN_MARGIN;
            btnY = screenH - BTN_HEIGHT - BTN_MARGIN;
        }

        // Check hover
        hovered = mouseX >= btnX && mouseX <= btnX + BTN_WIDTH &&
                  mouseY >= btnY && mouseY <= btnY + BTN_HEIGHT;

        pulseTimer += 0.016f;
        float pulse = (float) (Math.sin(pulseTimer * 3.0) * 0.5 + 0.5);

        // Setup GL
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);

        // Glow (outer)
        if (hovered || dragging) {
            setGLColor(COLOR_GLOW, 0.5f + pulse * 0.3f);
            drawRect(btnX - 4, btnY - 4, BTN_WIDTH + 8, BTN_HEIGHT + 8);
        }

        // Background
        setGLColor(hovered || dragging ? COLOR_HOVER_BG : COLOR_BG, 1f);
        drawRect(btnX, btnY, BTN_WIDTH, BTN_HEIGHT);

        // Border
        setGLColor(COLOR_BORDER, hovered ? 1f : 0.6f + pulse * 0.2f);
        drawRectOutline(btnX, btnY, BTN_WIDTH, BTN_HEIGHT, 1.5f);

        // Icon: "N" letter
        drawNexusIcon(btnX + BTN_WIDTH / 2f, btnY + BTN_HEIGHT / 2f, hovered ? 1f : 0.8f);

        // Connection indicator (small dot)
        float dotR = 3f;
        float dotX = btnX + BTN_WIDTH - dotR - 2f;
        float dotY = btnY + dotR + 2f;
        setGLColor(COLOR_CONNECTED, 0.7f + pulse * 0.3f);
        drawCircle(dotX, dotY, dotR, 8);

        // Restore GL
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    private void drawNexusIcon(float cx, float cy, float alpha) {
        float s = 8f;
        setGLColor(COLOR_ICON, alpha);
        GL11.glLineWidth(2.5f);
        GL11.glBegin(GL11.GL_LINE_STRIP);
        GL11.glVertex2f(cx - s * 0.6f, cy - s);
        GL11.glVertex2f(cx - s * 0.6f, cy + s);
        GL11.glVertex2f(cx + s * 0.6f, cy - s);
        GL11.glVertex2f(cx + s * 0.6f, cy + s);
        GL11.glEnd();
        GL11.glLineWidth(1f);
    }

    // ========================================================================
    // CampaignInputListener - Handle clicks and dragging
    // ========================================================================

    public int getListenerInputPriority() {
        return 100;
    }

    public void processCampaignInputPreCore(List<InputEventAPI> events) {
        for (InputEventAPI event : events) {
            if (event.isConsumed()) continue;

            if (event.isMouseMoveEvent()) {
                mouseX = event.getX();
                mouseY = event.getY();

                if (mouseDown && !dragging) {
                    float dx = mouseX - dragStartX;
                    float dy = mouseY - dragStartY;
                    if (dx * dx + dy * dy > DRAG_THRESHOLD * DRAG_THRESHOLD) {
                        dragging = true;
                    }
                }

                if (dragging) {
                    btnX = mouseX - dragOffsetX;
                    btnY = mouseY - dragOffsetY;
                    event.consume();
                }
            }

            if (event.isLMBDownEvent()) {
                if (isOverButton(event.getX(), event.getY())) {
                    mouseDown = true;
                    dragging = false;
                    dragStartX = event.getX();
                    dragStartY = event.getY();
                    dragOffsetX = event.getX() - btnX;
                    dragOffsetY = event.getY() - btnY;
                    event.consume();
                }
            }

            if (event.isLMBUpEvent()) {
                if (mouseDown) {
                    if (!dragging && isOverButton(event.getX(), event.getY())) {
                        openDashboard();
                    }
                    mouseDown = false;
                    dragging = false;
                    event.consume();
                }
            }
        }
    }

    public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {}
    public void processCampaignInputPostCore(List<InputEventAPI> events) {}

    private boolean isOverButton(int x, int y) {
        return x >= btnX && x <= btnX + BTN_WIDTH &&
               y >= btnY && y <= btnY + BTN_HEIGHT;
    }

    private void openDashboard() {
        try {
            SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    NexusFrame.toggle(port);
                }
            });
            log.info("NexusUI: Toggled dashboard frame");
        } catch (Exception e) {
            log.error("NexusUI: Failed to open dashboard", e);
        }
    }

    // ========================================================================
    // GL Drawing helpers
    // ========================================================================

    private void setGLColor(Color c, float alphaMult) {
        GL11.glColor4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f,
                       (c.getAlpha() / 255f) * alphaMult);
    }

    private void drawRect(float x, float y, float w, float h) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x, y + h);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x + w, y);
        GL11.glEnd();
    }

    private void drawRectOutline(float x, float y, float w, float h, float lineW) {
        GL11.glLineWidth(lineW);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x, y + h);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x + w, y);
        GL11.glEnd();
        GL11.glLineWidth(1f);
    }

    private void drawCircle(float cx, float cy, float r, int segments) {
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex2f(cx, cy);
        for (int i = 0; i <= segments; i++) {
            float angle = (float) (2.0 * Math.PI * i / segments);
            GL11.glVertex2f(cx + (float) Math.cos(angle) * r,
                            cy + (float) Math.sin(angle) * r);
        }
        GL11.glEnd();
    }
}
