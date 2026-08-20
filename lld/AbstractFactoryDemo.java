// Abstract Factory — one object that produces a whole matched family.
// Run: java AbstractFactoryDemo.java

// ─────────────────────────────────────────────────────────────
// 1. THE PRODUCTS — one interface per kind of thing in the family
// ─────────────────────────────────────────────────────────────
interface Button {
    void render();
    void onClick();
}

interface Checkbox {
    void render();
    void toggle();
}

interface Scrollbar {
    void render();
}

// ─────────────────────────────────────────────────────────────
// 2. CONCRETE PRODUCTS — one full set per family
// ─────────────────────────────────────────────────────────────
class MacButton implements Button {
    public void render()  { System.out.println("  ( rounded Mac button )"); }
    public void onClick() { System.out.println("  mac: soft click"); }
}
class MacCheckbox implements Checkbox {
    public void render()  { System.out.println("  ◉ Mac checkbox"); }
    public void toggle()  { System.out.println("  mac: animated tick"); }
}
class MacScrollbar implements Scrollbar {
    public void render()  { System.out.println("  ▏thin overlay scrollbar"); }
}

class WindowsButton implements Button {
    public void render()  { System.out.println("  [ square Windows button ]"); }
    public void onClick() { System.out.println("  win: click"); }
}
class WindowsCheckbox implements Checkbox {
    public void render()  { System.out.println("  ☑ Windows checkbox"); }
    public void toggle()  { System.out.println("  win: instant tick"); }
}
class WindowsScrollbar implements Scrollbar {
    public void render()  { System.out.println("  █ chunky scrollbar with arrows"); }
}

// ─────────────────────────────────────────────────────────────
// 3. THE ABSTRACT FACTORY — declares the whole family, no concretes
// ─────────────────────────────────────────────────────────────
interface UIFactory {
    Button    createButton();
    Checkbox  createCheckbox();
    Scrollbar createScrollbar();
}

// ─────────────────────────────────────────────────────────────
// 4. CONCRETE FACTORIES — each returns one consistent family
// ─────────────────────────────────────────────────────────────
class MacFactory implements UIFactory {
    public Button    createButton()    { return new MacButton(); }
    public Checkbox  createCheckbox()  { return new MacCheckbox(); }
    public Scrollbar createScrollbar() { return new MacScrollbar(); }
}

class WindowsFactory implements UIFactory {
    public Button    createButton()    { return new WindowsButton(); }
    public Checkbox  createCheckbox()  { return new WindowsCheckbox(); }
    public Scrollbar createScrollbar() { return new WindowsScrollbar(); }
}

// ─────────────────────────────────────────────────────────────
// 5. THE CLIENT — depends only on the interfaces.
//    Note there is no `if (mac)` anywhere below this line.
// ─────────────────────────────────────────────────────────────
class SettingsDialog {
    private final Button    okButton;
    private final Checkbox  rememberMe;
    private final Scrollbar scrollbar;

    SettingsDialog(UIFactory factory) {
        this.okButton   = factory.createButton();
        this.rememberMe = factory.createCheckbox();
        this.scrollbar  = factory.createScrollbar();
    }

    void render() {
        okButton.render();
        rememberMe.render();
        scrollbar.render();
    }

    void simulateUse() {
        rememberMe.toggle();
        okButton.onClick();
    }
}

// ─────────────────────────────────────────────────────────────
// 6. COMPOSITION ROOT — the ONE place that knows which family
// ─────────────────────────────────────────────────────────────
public class AbstractFactoryDemo {

    static UIFactory factoryFor(String os) {
        return switch (os) {
            case "mac"     -> new MacFactory();
            case "windows" -> new WindowsFactory();
            default        -> throw new IllegalArgumentException("unsupported OS: " + os);
        };
    }

    public static void main(String[] args) {
        for (String os : new String[]{"mac", "windows"}) {
            System.out.println(os + ":");

            UIFactory factory = factoryFor(os);        // decided once
            SettingsDialog dialog = new SettingsDialog(factory);

            dialog.render();
            dialog.simulateUse();
            System.out.println();
        }

        // The point: SettingsDialog was compiled once and never mentions
        // Mac or Windows. Swapping the family swapped every widget together.
    }
}
