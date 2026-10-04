package com.sri.frumacolor;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class ColorMenuScreen extends Screen {

    public ColorMenuScreen() {
        super(Component.literal("Fruma Color"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 2 - 80;

        // header (a disabled button used as a label)
        Button header = Button.builder(Component.literal("Fruma Color - pick a color"), b -> { })
            .bounds(cx - 102, y, 204, 20).build();
        header.active = false;
        addRenderableWidget(header);

        // six preset buttons in two columns
        for (int i = 0; i < FrumaColorClient.PRESET_NAMES.length; i++) {
            final String name = FrumaColorClient.PRESET_NAMES[i];
            final int rgb = FrumaColorClient.PRESET_COLORS[i];
            int bx = cx - 102 + (i % 2) * 106;
            int by = y + 28 + (i / 2) * 24;
            addRenderableWidget(Button.builder(
                    Component.literal(name).withStyle(style -> style.withColor(rgb)),
                    b -> FrumaColorClient.applyPreset(rgb, name))
                .bounds(bx, by, 98, 20).build());
        }

        // shift / solid toggle
        int toggleY = y + 28 + 3 * 24 + 6;
        final Button[] holder = new Button[1];
        holder[0] = Button.builder(modeLabel(), b -> {
                FrumaColorClient.setShift(!FrumaColorClient.config.shift);
                b.setMessage(modeLabel());
            })
            .bounds(cx - 102, toggleY, 204, 20).build();
        addRenderableWidget(holder[0]);

        // close
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose())
            .bounds(cx - 102, toggleY + 24, 204, 20).build());
    }

    private static Component modeLabel() {
        return Component.literal(FrumaColorClient.config.shift
            ? "Mode: Shift (keeps the multicolor look)"
            : "Mode: Solid (one flat color)");
    }
}
