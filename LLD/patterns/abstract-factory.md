# Abstract Factory

A factory that makes a **matched set**, so you can never mix families.

```bash
java AbstractFactoryDemo.java
```

## The problem

You need a Button and a Checkbox. On Mac both should look Mac; on Windows both
Windows.

```java
Button   b = isMac ? new MacButton()   : new WindowsButton();
Checkbox c = isMac ? new MacCheckbox() : new WindowsCheckbox();
```

That `isMac` check now lives in fifty places. Miss one and you ship a Mac button
beside a Windows checkbox. Nothing errors — it just looks broken.

## The shape

```
1. PRODUCT INTERFACES     Button, Checkbox, Scrollbar
2. CONCRETE PRODUCTS      MacButton..., WindowsButton...     one full set per family
3. ABSTRACT FACTORY       interface UIFactory                declares the whole family
4. CONCRETE FACTORIES     MacFactory, WindowsFactory         each returns one family
5. CLIENT                 SettingsDialog                     knows only the interfaces
6. COMPOSITION ROOT       factoryFor(os)                     the one place that decides
```

```java
interface UIFactory {
    Button    createButton();
    Checkbox  createCheckbox();
    Scrollbar createScrollbar();
}
```

The client:

```java
class SettingsDialog {
    SettingsDialog(UIFactory factory) {
        this.okButton   = factory.createButton();
        this.rememberMe = factory.createCheckbox();
        this.scrollbar  = factory.createScrollbar();
    }
}
```

Search that class for "Mac" or "Windows" — they aren't there. It compiles once
and produces two completely different UIs.

The only line that knows:

```java
UIFactory factory = switch (os) {
    case "mac"     -> new MacFactory();
    case "windows" -> new WindowsFactory();
};
```

## Output

```
mac:
  ( rounded Mac button )
  ◉ Mac checkbox
  ▏thin overlay scrollbar

windows:
  [ square Windows button ]
  ☑ Windows checkbox
  █ chunky scrollbar with arrows
```

## vs Factory Method

```
Factory Method    one method  -> ONE product      "make me a Button"
Abstract Factory  one object  -> a MATCHED SET    "make me a whole Mac UI"
```

Abstract Factory exists specifically because the products must agree with each
other. If you only ever create one kind of thing, you want Factory Method.

## The tradeoff to say out loud

```
adding a new FAMILY  (Linux)   easy — 4 new classes, 1 switch line, nothing else changes
adding a new PRODUCT (Slider)  painful — change the interface AND every factory
```

Right choice when families come and go. Wrong choice when you keep adding
product types.

## Remembering it

> A factory makes one thing. An abstract factory makes a matching outfit.
> You pick "formal" or "casual" once, and the shoes, shirt and trousers all agree.
