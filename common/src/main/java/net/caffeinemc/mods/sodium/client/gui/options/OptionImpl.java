package net.caffeinemc.mods.sodium.client.gui.options;

import me.flashyreese.mods.reeses_sodium_options.client.gui.OptionExtended;
import net.caffeinemc.mods.sodium.client.gui.options.binding.GenericBinding;
import net.caffeinemc.mods.sodium.client.gui.options.binding.OptionBinding;
import net.caffeinemc.mods.sodium.client.gui.options.control.Control;
import net.caffeinemc.mods.sodium.client.gui.options.storage.OptionStorage;
import net.caffeinemc.mods.sodium.client.util.Dim2i;
import net.minecraft.text.Text;
import org.apache.commons.lang3.Validate;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

public class OptionImpl<S, T> implements OptionExtended<T> {
    private final OptionStorage<S> storage;

    private final OptionBinding<S, T> binding;
    private final Control<T> control;

    private final EnumSet<OptionFlag> flags;

    private final Text name;
    private final Function<T, Text> tooltip;

    private final OptionImpact impact;

    private T value;
    private T modifiedValue;

    private final BooleanSupplier enabled;

    private Dim2i parentDimension;
    private Dim2i dim2i;
    private boolean highlight;
    private boolean selected;


    private OptionImpl(OptionStorage<S> storage,
                       Text name,
                       Function<T, Text> tooltip,
                       OptionBinding<S, T> binding,
                       Function<OptionImpl<S, T>, Control<T>> control,
                       EnumSet<OptionFlag> flags,
                       OptionImpact impact,
                       BooleanSupplier enabled) {
        this.storage = storage;
        this.name = name;
        this.tooltip = tooltip;
        this.binding = binding;
        this.impact = impact;
        this.flags = flags;
        this.control = control.apply(this);
        this.enabled = enabled;

        this.reset();
    }

    @Override
    public Text getName() {
        return this.name;
    }

    @Override
    public Text getTooltip() {
        return this.tooltip.apply(this.modifiedValue);
    }

    @Override
    public OptionImpact getImpact() {
        return this.impact;
    }

    @Override
    public Control<T> getControl() {
        return this.control;
    }

    @Override
    public T getValue() {
        return this.modifiedValue;
    }

    @Override
    public void setValue(T value) {
        this.modifiedValue = value;
    }

    @Override
    public void reset() {
        this.value = this.binding.getValue(this.storage.getData());
        this.modifiedValue = this.value;
    }

    @Override
    public OptionStorage<?> getStorage() {
        return this.storage;
    }

    @Override
    public boolean isAvailable() {
        return this.enabled.getAsBoolean();
    }

    @Override
    public boolean hasChanged() {
        return !this.value.equals(this.modifiedValue);
    }

    @Override
    public void applyChanges() {
        this.binding.setValue(this.storage.getData(), this.modifiedValue);
        this.value = this.modifiedValue;
    }

    @Override
    public Collection<OptionFlag> getFlags() {
        return this.flags;
    }

    public static <S, T> OptionImpl.Builder<S, T> createBuilder(@SuppressWarnings("unused") Class<T> type, OptionStorage<S> storage) {
        return new Builder<>(storage);
    }

    @Override
    public boolean isHighlight() {
        return this.highlight;
    }

    @Override
    public void setHighlight(boolean highlight) {
        this.highlight = highlight;
    }

    @Override
    public Dim2i getDim2i() {
        return this.dim2i;
    }

    @Override
    public void setDim2i(Dim2i dim2i) {
        this.dim2i = dim2i;
    }

    @Override
    public Dim2i getParentDimension() {
        return this.parentDimension;
    }

    @Override
    public void setParentDimension(Dim2i dim2i) {
        this.parentDimension = dim2i;
    }

    @Override
    public boolean isSelected() {
        return this.selected;
    }

    @Override
    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public static class Builder<S, T> {
        private final OptionStorage<S> storage;
        private Text name;
        private Function<T, Text> tooltip;
        private OptionBinding<S, T> binding;
        private Function<OptionImpl<S, T>, Control<T>> control;
        private OptionImpact impact;
        private final EnumSet<OptionFlag> flags = EnumSet.noneOf(OptionFlag.class);
        private BooleanSupplier enabled = () -> true;

        private Builder(OptionStorage<S> storage) {
            this.storage = storage;
        }

        public Builder<S, T> setName(Text name) {
            Validate.notNull(name, "Argument must not be null");

            this.name = name;

            return this;
        }

        public Builder<S, T> setTooltip(Text tooltip) {
            Validate.notNull(tooltip, "Argument must not be null");

            this.tooltip = t -> tooltip;

            return this;
        }

        public Builder<S, T> setTooltip(Function<T, Text> tooltip) {
            Validate.notNull(tooltip, "Argument must not be null");

            this.tooltip = tooltip;

            return this;
        }

        public Builder<S, T> setBinding(BiConsumer<S, T> setter, Function<S, T> getter) {
            Validate.notNull(setter, "Setter must not be null");
            Validate.notNull(getter, "Getter must not be null");

            this.binding = new GenericBinding<>(setter, getter);

            return this;
        }


        public Builder<S, T> setBinding(OptionBinding<S, T> binding) {
            Validate.notNull(binding, "Argument must not be null");

            this.binding = binding;

            return this;
        }

        public Builder<S, T> setControl(Function<OptionImpl<S, T>, Control<T>> control) {
            Validate.notNull(control, "Argument must not be null");

            this.control = control;

            return this;
        }

        public Builder<S, T> setImpact(OptionImpact impact) {
            this.impact = impact;

            return this;
        }

        public Builder<S, T> setEnabled(BooleanSupplier value) {
            this.enabled = value;

            return this;
        }

        public Builder<S, T> setFlags(OptionFlag... flags) {
            Collections.addAll(this.flags, flags);

            return this;
        }

        public OptionImpl<S, T> build() {
            Validate.notNull(this.name, "Name must be specified");
            Validate.notNull(this.tooltip, "Tooltip must be specified");
            Validate.notNull(this.binding, "Option binding must be specified");
            Validate.notNull(this.control, "Control must be specified");

            return new OptionImpl<>(this.storage, this.name, this.tooltip, this.binding, this.control, this.flags, this.impact, this.enabled);
        }
    }
}
