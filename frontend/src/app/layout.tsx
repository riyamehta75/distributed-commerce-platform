import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Common Market | Distributed Commerce",
  description: "A live storefront powered by the Distributed Commerce Platform API.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
