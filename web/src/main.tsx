import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { AppProviders } from "@/app/providers";
import { queryClient, router } from "@/app/router";
import "@/styles.css";

const container = document.getElementById("root");
if (!container) {
  throw new Error("Missing #root element");
}

createRoot(container).render(
  <StrictMode>
    <AppProviders router={router} queryClient={queryClient} />
  </StrictMode>,
);
