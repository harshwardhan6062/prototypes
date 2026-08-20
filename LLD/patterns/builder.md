# Builder — why the entity can't be its own builder

The question that starts this: if a builder is just fluent setters, why not put
them on the entity itself?

```java
Pizza p = new Pizza().setSize("L").setCheese(true).setCrust("thin");
```

That works, and plenty of real code does it. It gives up three things.

## 1. The object can never be immutable

```java
class Pizza {
    private String size;                              // can't be final — setters write it
    Pizza setSize(String s) { this.size = s; return this; }
}
```

So this stays legal forever:

```java
Pizza p = order.getPizza();
p.setSize("XXL");          // resized after payment
```

With a separate builder the fields are `final`, assigned once in a private
constructor. Nobody can change a `Pizza` after it exists.

## 2. Half-built objects are real objects

```java
Pizza p = new Pizza().setSize("L");
kitchen.cook(p);           // compiles. No crust, no cheese.
```

`new Pizza()` produced a valid-looking `Pizza` that isn't a pizza. It can be
stored, passed around, returned from a method — nothing stops it.

With a builder:

```java
Pizza p = new PizzaBuilder().size("L").build();
                                       // throws: crust is required
```

A `Pizza` cannot exist in an invalid state, because `build()` is the only way to
get one.

## 3. There is no moment to validate

`build()` is the single place the rules live:

```java
Pizza build() {
    if (crust == null)          throw new IllegalStateException("crust required");
    if (extraCheese && !cheese) throw new IllegalStateException("extra cheese needs cheese");
    return new Pizza(this);     // only now does a Pizza exist
}
```

A self-builder has no such moment. The object never knows when it is finished.

## Two smaller ones

**API pollution** — every consumer of `Pizza` now sees fifteen setters that only
made sense during construction.

**Required vs optional** — a builder can force required fields into its own
constructor (`new PizzaBuilder(size, crust)`) and leave the rest fluent. A
self-builder treats everything as optional.

## When self-setters are fine

Mutable objects where validation is trivial: config objects, DTOs, test
fixtures. The builder earns its cost when immutability matters, or when "invalid
object" must be impossible rather than merely discouraged.

## The line to say in an interview

> A builder exists so that the thing being built and the finished thing are
> different types. The half-built state lives in the builder, so the entity has
> no half-built state at all.

If the entity *is* the builder, those two are merged and the entity permanently
inherits the messy one's problems.
