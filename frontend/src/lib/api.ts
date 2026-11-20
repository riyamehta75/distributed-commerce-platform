const API_URL = (process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080").replace(/\/$/, "");

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers);
  headers.set("Content-Type", "application/json");
  const token = typeof window === "undefined" ? null : localStorage.getItem("commerce-token");
  if (token) headers.set("Authorization", `Bearer ${token}`);
  const response = await fetch(`${API_URL}${path}`, {
    ...init,
    headers,
    cache: "no-store",
  });
  if (!response.ok) {
    if (response.status === 401 && typeof window !== "undefined") {
      localStorage.removeItem("commerce-user");
      localStorage.removeItem("commerce-token");
      window.dispatchEvent(new Event("commerce-auth-expired"));
    }
    let message = `Request failed (${response.status})`;
    try {
      const body = await response.json();
      message = body.detail || body.message || body.error || message;
    } catch {
      // The response may not contain JSON.
    }
    throw new Error(message);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export type User = { id: string; email: string; name: string; role: "ADMIN" | "CUSTOMER" };
export type AuthResponse = { user: User; token: string; expiresInSeconds: number };
export type Product = { id: string; name: string; description: string | null; price: number };
export type Order = {
  id: string;
  userId: string;
  status: string;
  total: number;
  createdAt: string;
  items: { productId: string; quantity: number; unitPrice: number }[];
};
