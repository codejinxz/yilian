import { type QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "@tanstack/react-router";
import type { createAppRouter } from "@/app/router";

type AppRouter = ReturnType<typeof createAppRouter>;

/**
 * 应用级 Provider 组合：把同一个 QueryClient 与 router 注入组件树；测试可传入使用 memory history 的路由。
 * @param props 路由实例与 QueryClient
 */
export function AppProviders({
  router,
  queryClient,
}: {
  router: AppRouter;
  queryClient: QueryClient;
}) {
  return (
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  );
}
