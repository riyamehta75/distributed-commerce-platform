"use client";

import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import { api, AuthResponse, Order, Product, User } from "@/lib/api";

type CartLine = { product: Product; quantity: number };

export default function Home() {
  const [products, setProducts] = useState<Product[]>([]);
  const [cart, setCart] = useState<CartLine[]>([]);
  const [user, setUser] = useState<User | null>(null);
  const [orders, setOrders] = useState<Order[]>([]);
  const [activeOrder, setActiveOrder] = useState<Order | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [authMode, setAuthMode] = useState<"register" | "login">("register");

  const refreshProducts = useCallback(async () => {
    setProducts(await api<Product[]>("/api/products"));
  }, []);

  const refreshOrders = useCallback(async (userId: string) => {
    setOrders(await api<Order[]>(`/api/orders?userId=${encodeURIComponent(userId)}`));
  }, []);

  useEffect(() => {
    const expireSession = () => setUser(null);
    window.addEventListener("commerce-auth-expired", expireSession);
    refreshProducts().catch((e: Error) => setError(e.message));
    const stored = localStorage.getItem("commerce-user");
    const storedToken = localStorage.getItem("commerce-token");
    if (stored && storedToken) {
      const savedUser = JSON.parse(stored) as User;
      setUser(savedUser);
      refreshOrders(savedUser.id).catch((e: Error) => setError(e.message));
    } else if (stored) {
      localStorage.removeItem("commerce-user");
    }
    return () => window.removeEventListener("commerce-auth-expired", expireSession);
  }, [refreshProducts, refreshOrders]);

  const cartTotal = useMemo(
    () => cart.reduce((sum, line) => sum + line.product.price * line.quantity, 0),
    [cart],
  );

  function addToCart(product: Product) {
    setCart((current) => {
      const existing = current.find((line) => line.product.id === product.id);
      return existing
        ? current.map((line) => line.product.id === product.id ? { ...line, quantity: line.quantity + 1 } : line)
        : [...current, { product, quantity: 1 }];
    });
    setNotice(`${product.name} added to bag`);
  }

  async function submitAuth(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    setBusy(true);
    setError("");
    const form = new FormData(formElement);
    const payload = {
      email: String(form.get("email")),
      password: String(form.get("password")),
      ...(authMode === "register" ? { name: String(form.get("name")) } : {}),
    };
    try {
      const result = await api<AuthResponse>(`/api/users/${authMode}`, { method: "POST", body: JSON.stringify(payload) });
      setUser(result.user);
      localStorage.setItem("commerce-user", JSON.stringify(result.user));
      localStorage.setItem("commerce-token", result.token);
      await refreshOrders(result.user.id);
      setNotice(`Welcome${result.user.name ? `, ${result.user.name}` : ""}`);
      formElement.reset();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function checkout() {
    if (!user || cart.length === 0) return;
    setBusy(true);
    setError("");
    try {
      const order = await api<Order>("/api/orders", {
        method: "POST",
        body: JSON.stringify({
          userId: user.id,
          items: cart.map((line) => ({ productId: line.product.id, quantity: line.quantity })),
        }),
      });
      setActiveOrder(order);
      setCart([]);
      setNotice("Order reserved. Complete the simulated payment to confirm it.");
      await refreshOrders(user.id);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function pay(order: Order) {
    setBusy(true);
    setError("");
    try {
      await api("/api/payments", {
        method: "POST",
        body: JSON.stringify({ orderId: order.id, idempotencyKey: `storefront-${order.id}` }),
      });
      const updated = await api<Order>(`/api/orders/${order.id}`);
      setActiveOrder(updated);
      if (user) await refreshOrders(user.id);
      setNotice("Payment simulated successfully. Your order is confirmed.");
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function cancel(order: Order) {
    setBusy(true);
    setError("");
    try {
      const updated = await api<Order>(`/api/orders/${order.id}/cancel`, { method: "POST" });
      setActiveOrder(updated);
      if (user) await refreshOrders(user.id);
      setNotice("Order cancelled and reserved stock released.");
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function addDemoProduct(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    setBusy(true);
    setError("");
    const form = new FormData(formElement);
    try {
      const product = await api<Product>("/api/products", {
        method: "POST",
        body: JSON.stringify({
          name: String(form.get("productName")),
          description: String(form.get("description")),
          price: Number(form.get("price")),
        }),
      });
      await api(`/api/inventory/${product.id}`, {
        method: "PUT",
        body: JSON.stringify({ quantity: Number(form.get("quantity")) }),
      });
      await refreshProducts();
      formElement.reset();
      setNotice(`${product.name} published with inventory`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  function signOut() {
    localStorage.removeItem("commerce-user");
    localStorage.removeItem("commerce-token");
    setUser(null);
    setOrders([]);
    setNotice("Signed out");
  }

  return (
    <main>
      <header className="topbar">
        <a className="brand" href="#"><span className="brand-mark">C</span> COMMON MARKET</a>
        <nav><a href="#shop">Shop</a><a href="#how">How it works</a>{user?.role === "ADMIN" && <a href="#seller">Seller studio</a>}</nav>
        <a className="bag-link" href="#bag">Bag <span>{cart.reduce((sum, line) => sum + line.quantity, 0)}</span></a>
      </header>

      <section className="hero">
        <div className="hero-copy">
          <p className="eyebrow">A BETTER KIND OF MARKETPLACE</p>
          <h1>Good things,<br /><em>made to last.</em></h1>
          <p className="hero-sub">Thoughtful objects from independent makers. Discover your next everyday favorite.</p>
          <a className="button dark" href="#shop">Explore the collection <span>↘</span></a>
          <div className="hero-foot"><span>01 — OBJECTS WITH A STORY</span><span>CURATED FOR EVERYDAY LIVING</span></div>
        </div>
        <div className="hero-art" aria-label="Featured handcrafted ceramic vase">
          <div className="sun" /><div className="art-label">STUDIO<br />NO. 08</div>
          <div className="vase"><div className="vase-neck" /><div className="vase-body" /></div>
          <div className="art-caption"><span>THE SANDSTONE VASE</span><span>HAND-FINISHED · PORTLAND, OR</span></div>
        </div>
      </section>

      <section className="ticker"><span>MADE WITH INTENTION</span><i>✳</i><span>BUILT FOR THE LONG RUN</span><i>✳</i><span>SMALL-BATCH. BIG HEART.</span><i>✳</i><span>MADE WITH INTENTION</span></section>

      <section className="shop section" id="shop">
        <div className="section-head"><div><p className="eyebrow">THE MARKET</p><h2>Find your <em>everyday.</em></h2></div><p className="muted">{products.length} considered pieces</p></div>
        {error && <div className="alert error">{error}</div>}
        {notice && <div className="alert success">{notice}</div>}
        {products.length === 0 ? <div className="empty"><span>✳</span><h3>The shelves are ready.</h3><p>{user?.role === "ADMIN" ? "Add your first product in Seller Studio below to bring the market to life." : "Check back soon for our first collection."}</p></div> :
          <div className="product-grid">{products.map((product, index) => <article className="product-card" key={product.id}>
            <div className={`product-image tone-${index % 4}`}><span className="product-index">0{index + 1}</span><div className="product-shape"><i /></div><button onClick={() => addToCart(product)} aria-label={`Add ${product.name} to bag`}>＋</button></div>
            <div className="product-details"><div><h3>{product.name}</h3><p>{product.description || "A considered piece for everyday living."}</p></div><strong>${Number(product.price).toFixed(2)}</strong></div>
          </article>)}</div>}
      </section>

      <section className="purchase-layout section">
        <div className="panel" id="bag">
          <p className="eyebrow">YOUR SELECTION</p><h2>The bag <em>({cart.reduce((sum, line) => sum + line.quantity, 0)})</em></h2>
          {cart.length === 0 ? <p className="muted">Your bag is taking a well-deserved breather.</p> : <>
            <div className="cart-lines">{cart.map((line) => <div className="cart-line" key={line.product.id}><div><strong>{line.product.name}</strong><span>Qty {line.quantity}</span></div><strong>${(line.product.price * line.quantity).toFixed(2)}</strong></div>)}</div>
            <div className="total"><span>Total</span><strong>${cartTotal.toFixed(2)}</strong></div>
            <button className="button dark full" disabled={!user || busy} onClick={checkout}>{user ? (busy ? "Placing order…" : "Reserve & place order") : "Sign in to checkout"}</button>
          </>}
        </div>
        <div className="panel order-panel">
          <p className="eyebrow">ORDER DESK</p><h2>Track the <em>journey.</em></h2>
          {activeOrder && <div className="order-callout">
            <div className="order-heading"><strong>Order {activeOrder.id.slice(0, 8).toUpperCase()}</strong><span className={`status ${activeOrder.status.toLowerCase()}`}>{activeOrder.status.replaceAll("_", " ")}</span></div>
            <p>{activeOrder.items.length} line item(s) · ${Number(activeOrder.total).toFixed(2)}</p>
            {activeOrder.status === "AWAITING_PAYMENT" && <div className="button-row"><button className="button dark" disabled={busy} onClick={() => pay(activeOrder)}>Simulate payment</button><button className="button outline" disabled={busy} onClick={() => cancel(activeOrder)}>Cancel</button></div>}
          </div>}
          {!user ? <div className="auth">
            <div className="switch"><button className={authMode === "register" ? "selected" : ""} onClick={() => setAuthMode("register")}>Create account</button><button className={authMode === "login" ? "selected" : ""} onClick={() => setAuthMode("login")}>Sign in</button></div>
            <form onSubmit={submitAuth} className="form">
              {authMode === "register" && <label>Your name<input name="name" required maxLength={255} placeholder="Alex Morgan" /></label>}
              <label>Email address<input name="email" type="email" required placeholder="you@example.com" /></label>
              <label>Password<input name="password" type="password" minLength={8} required placeholder="At least 8 characters" /></label>
              <button className="button dark full" disabled={busy}>{busy ? "Please wait…" : authMode === "register" ? "Create your account" : "Sign in"}</button>
            </form>
          </div> : <div className="account"><span>SIGNED IN AS</span><strong>{user.name}</strong><small>{user.email}</small><button className="text-button" onClick={signOut}>Sign out</button></div>}
          {user && <div className="history"><div className="history-head"><span>RECENT ORDERS</span><button className="text-button" onClick={() => refreshOrders(user.id).catch((e: Error) => setError(e.message))}>Refresh</button></div>
            {orders.length === 0 ? <p className="muted">Your first order will show up here.</p> : orders.slice(0, 4).map((order) => <button className="history-row" key={order.id} onClick={() => setActiveOrder(order)}><span>#{order.id.slice(0, 8).toUpperCase()}<small>{new Date(order.createdAt).toLocaleDateString()}</small></span><strong>{order.status.replaceAll("_", " ")}</strong></button>)}</div>}
        </div>
      </section>

      {user?.role === "ADMIN" && <section className="seller section" id="seller">
        <div className="seller-intro"><p className="eyebrow">FOR THE MAKERS</p><h2>Put your work<br />out <em>in the world.</em></h2><p>Seller Studio creates a catalog listing and initializes stock in one quick step.</p></div>
        <form className="seller-form" onSubmit={addDemoProduct}>
          <div className="field-pair"><label>Product name<input name="productName" required maxLength={255} placeholder="Hand-thrown ceramic cup" /></label><label>Price (USD)<input name="price" type="number" step="0.01" min="0.01" required placeholder="32.00" /></label></div>
          <label>Short description<input name="description" placeholder="A little detail about what makes it special" /></label>
          <div className="field-pair"><label>Starting quantity<input name="quantity" type="number" min="0" required defaultValue="12" /></label><button className="button light" disabled={busy}>{busy ? "Publishing…" : "Publish product ↗"}</button></div>
        </form>
      </section>}

      <section className="how section" id="how"><p className="eyebrow">A LITTLE MORE THOUGHTFUL</p><h2>Good from <em>start to finish.</em></h2><div className="steps"><div><span>01</span><h3>Discover</h3><p>Explore a curated collection of useful, beautifully made things.</p></div><div><span>02</span><h3>Reserve</h3><p>Inventory is held safely while your order is waiting for payment.</p></div><div><span>03</span><h3>Enjoy</h3><p>Complete a simulated payment and watch your order become confirmed.</p></div></div></section>

      <footer><a className="brand" href="#"><span className="brand-mark">C</span> COMMON MARKET</a><span>AN EXPERIMENT IN BETTER COMMERCE</span><span>© 2026 COMMON MARKET</span></footer>
    </main>
  );
}
