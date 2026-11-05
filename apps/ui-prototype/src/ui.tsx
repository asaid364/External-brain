/* apps/ui-prototype/src/ui.tsx */
import React, { ReactNode } from 'react';
import './style.css';

export type Section = 'records' | 'reports' | 'data';

export interface ShellProps {
  section: Section;
  children: ReactNode;
}

export const Shell = ({ section, children }: ShellProps) => {
  return (
    <div className="app-shell">
      {/* 桌面端左侧边栏导航 / 移动端顶部标题与说明 */}
      <aside className="shell-sidebar" aria-label="应用主导航">
        <header className="shell-brand">
          <div className="brand-header">
            <span className="eyebrow">生活记录</span>
            <h1 className="brand-title">本地只读视图</h1>
          </div>
          <div className="brand-badges">
            <span className="badge badge-subtle">只读框架 · 合成示例</span>
          </div>
          <p className="brand-caption">
            仅作客户端本地展示。无后端服务、无真实 AI、无网络同步。
          </p>
        </header>

        <nav className="desktop-nav" aria-label="页面切换">
          <a
            href="#records"
            className={`nav-item ${section === 'records' ? 'selected' : ''}`}
            aria-current={section === 'records' ? 'page' : undefined}
          >
            <span className="nav-title">记录</span>
            <span className="nav-desc">日常随手速记</span>
          </a>
          <a
            href="#reports"
            className={`nav-item ${section === 'reports' ? 'selected' : ''}`}
            aria-current={section === 'reports' ? 'page' : undefined}
          >
            <span className="nav-title">日报</span>
            <span className="nav-desc">结构化小结概览</span>
          </a>
          <a
            href="#data"
            className={`nav-item ${section === 'data' ? 'selected' : ''}`}
            aria-current={section === 'data' ? 'page' : undefined}
          >
            <span className="nav-title">数据与同步</span>
            <span className="nav-desc">本地存储信息摘要</span>
          </a>
        </nav>

        <footer className="shell-aside-footer">
          <div className="notice-card">
            <div className="status-label">运行模式</div>
            <p className="subtle">只读浏览 · 不包含编辑、保存或网络重试等业务操作</p>
          </div>
        </footer>
      </aside>

      {/* 主展示区 */}
      <main className="shell-main">
        {children}
      </main>

      {/* 移动端底部固定三入口导航 */}
      <nav className="mobile-tabbar" aria-label="移动端底栏导航">
        <a
          href="#records"
          className={`tab-item ${section === 'records' ? 'selected' : ''}`}
          aria-current={section === 'records' ? 'page' : undefined}
        >
          <span className="tab-label">记录</span>
        </a>
        <a
          href="#reports"
          className={`tab-item ${section === 'reports' ? 'selected' : ''}`}
          aria-current={section === 'reports' ? 'page' : undefined}
        >
          <span className="tab-label">日报</span>
        </a>
        <a
          href="#data"
          className={`tab-item ${section === 'data' ? 'selected' : ''}`}
          aria-current={section === 'data' ? 'page' : undefined}
        >
          <span className="tab-label">数据与同步</span>
        </a>
      </nav>
    </div>
  );
};

