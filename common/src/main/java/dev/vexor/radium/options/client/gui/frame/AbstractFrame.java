package dev.vexor.radium.options.client.gui.frame;

import dev.vexor.radium.compat.mojang.minecraft.gui.Renderable;
import dev.vexor.radium.compat.mojang.minecraft.gui.event.GuiEventListener;
import dev.vexor.radium.compat.mojang.minecraft.gui.event.GuiParentEventListener;
import net.caffeinemc.mods.sodium.client.gui.options.control.ControlElement;
import net.caffeinemc.mods.sodium.client.gui.widgets.AbstractWidget;
import net.caffeinemc.mods.sodium.client.util.Dim2i;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Window;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

public abstract class AbstractFrame extends AbstractWidget implements GuiParentEventListener {
    protected final Dim2i dim;
    protected final List<AbstractWidget> children = new ArrayList<>();
    protected final List<Renderable> drawable = new ArrayList<>();
    protected final List<ControlElement<?>> controlElements = new ArrayList<>();
    protected boolean renderOutline;
    private GuiEventListener focused;
    private boolean dragging;

    public AbstractFrame(Dim2i dim, boolean renderOutline) {
        this.dim = dim;
        this.renderOutline = renderOutline;
    }

    public void buildFrame() {
        for (GuiEventListener element : this.children) {
            if (element instanceof AbstractFrame abstractFrame) {
                this.controlElements.addAll(abstractFrame.controlElements);
            }
            if (element instanceof ControlElement<?> controlElement) {
                this.controlElements.add(controlElement);
            }
            if (element instanceof Renderable drawable) {
                this.drawable.add(drawable);
            }
        }
    }

    @Override
    public void render(int mouseX, int mouseY, float delta) {
        if (this.renderOutline) {
            this.drawRectOutline(this.dim.getOriginX(), this.dim.getOriginY(), this.dim.getLimitX(), this.dim.getLimitY(), 0xFFAAAAAA);
        }
        for (Renderable drawable : this.drawable) {
            drawable.render(mouseX, mouseY, delta);
        }
    }

    public void applyScissor(int x, int y, int width, int height, Runnable action) {
        final double scale = new Window(MinecraftClient.getInstance()).getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor((int) (x * scale), (int) (MinecraftClient.getInstance().getFramebuffer().viewportHeight - (y + height) * scale), (int) (width * scale), (int) (height * scale));
        action.run();
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    protected void drawRectOutline(double x, double y, double w, double h, int color) {
        final float a = (float) (color >> 24 & 255) / 255.0F;
        final float r = (float) (color >> 16 & 255) / 255.0F;
        final float g = (float) (color >> 8 & 255) / 255.0F;
        final float b = (float) (color & 255) / 255.0F;

        this.drawQuads(vertices -> {
            addQuad(vertices, x, y, w, y + 1, a, r, g, b);
            addQuad(vertices, x, h - 1, w, h, a, r, g, b);
            addQuad(vertices, x, y, x + 1, h, a, r, g, b);
            addQuad(vertices, w - 1, y, w, h, a, r, g, b);
        });
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return this.dim.containsCursor(mouseX, mouseY);
    }

    @Nullable
    @Override
    public GuiEventListener getFocused() {
        return this.focused;
    }

    @Override
    public void setFocused(@Nullable GuiEventListener focused) {
        this.focused = focused;
    }

    @Override
    public List<? extends GuiEventListener> children() {
        return this.children;
    }

    @Override
    public boolean isDragging() {
        return this.dragging;
    }

    @Override
    public void setDragging(boolean dragging) {
        this.dragging = dragging;
    }
}