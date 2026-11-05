import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { Shell, type Section } from "./ui";
import {
  entries,
  reports,
  dateLabel,
  timeLabel,
  type Entry,
  type Report,
} from "./model";

function routeFromHash() {
  const raw = window.location.hash.slice(1) || "records";
  const [path, query = ""] = raw.split("?");
  const parts = path.split("/");
  const section: Section =
    parts[0] === "reports"
      ? "reports"
      : parts[0] === "data"
        ? "data"
        : "records";
  return {
    raw,
    section,
    id: parts[1],
    source: parts[2] === "source" ? parts[3] : undefined,
    date: new URLSearchParams(query).get("date") || "",
  };
}
function Empty({
  title,
  children,
}: {
  title: string;
  children?: React.ReactNode;
}) {
  return (
    <div className="empty-state">
      <h3>{title}</h3>
      {children && <p>{children}</p>}
    </div>
  );
}
function RecordDetail({
  entry,
  back,
  backLabel,
}: {
  entry: Entry | undefined;
  back: string;
  backLabel: string;
}) {
  return (
    <>
      <a className="back-link" href={back}>
        ← {backLabel}
      </a>
      {!entry ? (
        <Empty title="来源暂不可查看">
          没有可展示的合成原文，不推断或补造内容。
        </Empty>
      ) : (
        <article aria-label="记录详情">
          <header className="detail-header">
            <p className="eyebrow">原始记录 · 合成示例</p>
            <h2>{dateLabel(entry.occurred.slice(0, 10))}的记录</h2>
          </header>
          <div className="prose">{entry.text}</div>
          <dl className="metadata">
            <dt>发生时间</dt>
            <dd>{timeLabel(entry.occurred)}</dd>
            <dt>首次录入时间</dt>
            <dd>{timeLabel(entry.created)}</dd>
            {entry.history.length > 0 && (
              <>
                <dt>最近修改时间</dt>
                <dd>
                  {timeLabel(entry.history[entry.history.length - 1].modified)}
                </dd>
              </>
            )}
          </dl>
          {entry.history.length > 0 && (
            <details className="history">
              <summary>修改历史 · {entry.history.length} 次</summary>
              {[...entry.history].reverse().map((h, i) => (
                <section key={h.modified} aria-label="历史版本">
                  <h3>{timeLabel(h.modified)}</h3>
                  <p className="subtle">
                    第 {entry.history.length - i} 次修改 · 只读合成示例
                  </p>
                  <h4>修改前</h4>
                  <p className="prose">{h.before.text}</p>
                  <p className="subtle">
                    发生时间：{timeLabel(h.before.occurred)}
                  </p>
                  <h4>修改后</h4>
                  <p className="prose">{h.after.text}</p>
                  <p className="subtle">
                    发生时间：{timeLabel(h.after.occurred)}
                  </p>
                </section>
              ))}
            </details>
          )}
        </article>
      )}
    </>
  );
}
function ReportDetail({ report }: { report: Report }) {
  return (
    <article aria-label="日报详情">
      <a href="#reports" className="back-link">
        ← 返回日报列表
      </a>
      <header className="detail-header">
        <p className="eyebrow">事实日报 · 合成示例</p>
        <h2>{dateLabel(report.date)}</h2>
        <p className="subtle">{report.date} · 仅整理事实，不评价成长</p>
      </header>
      {report.changedSources && (
        <p className="notice">
          来源记录已有修改，本日报保留原版本。可查看来源核对；本框架不会自动改写正文。
        </p>
      )}
      {report.items.map((item, i) => (
        <section className="report-entry" key={i}>
          <h3>{item.time}</h3>
          <p className="prose">{item.text}</p>
          <div className="source-links">
            {item.sources.length === 1 ? (
              <a href={`#reports/${report.date}/source/${item.sources[0]}`}>
                查看来源记录 →
              </a>
            ) : (
              <details>
                <summary>查看 {item.sources.length} 条来源记录</summary>
                {item.sources.map((id, j) => (
                  <p key={id}>
                    <a href={`#reports/${report.date}/source/${id}`}>
                      来源 {j + 1} ·{" "}
                      {entries
                        .find((e) => e.id === id)
                        ?.occurred.replace("T", " ") ?? "暂不可查看"}{" "}
                      →
                    </a>
                  </p>
                ))}
              </details>
            )}
          </div>
        </section>
      ))}
      <details className="history">
        <summary>版本与形成信息</summary>
        <dl className="metadata">
          <dt>形成时间（示例）</dt>
          <dd>{timeLabel(report.created)}</dd>
          <dt>当前版本</dt>
          <dd>{report.manual ? "经过手动修改的示例" : "整理稿示例"}</dd>
          {report.modified && (
            <>
              <dt>最近修改时间</dt>
              <dd>{timeLabel(report.modified)}</dd>
            </>
          )}
        </dl>
        <p className="subtle">
          本文是预先编写的合成示例，并非真实 AI 生成结果。
        </p>
        {report.versions.length > 0 && (
          <details>
            <summary>历史版本 · {report.versions.length} 份</summary>
            {[...report.versions].reverse().map((v) => (
              <section key={v.at}>
                <h3>{v.label}</h3>
                <p className="subtle">{timeLabel(v.at)}</p>
                <p className="prose">{v.text}</p>
              </section>
            ))}
          </details>
        )}
      </details>
    </article>
  );
}
function DataOverview() {
  return (
    <section aria-label="数据与同步概览">
      <header className="page-heading">
        <p className="eyebrow">数据的去向</p>
        <h1>数据与同步</h1>
        <p className="lede">本机保存与跨设备传输，是两件不同的事。</p>
      </header>
      <p className="notice">
        当前只读框架未接入正式数据保存、真实 AI
        或设备同步。以下为结构说明，不是实时设备状态。
      </p>
      <div className="data-grid">
        <section className="info-block">
          <p className="eyebrow">01 / 本机</p>
          <h2>
            <span className="mobile-copy">手机本机数据</span>
            <span className="desktop-copy">电脑本机数据</span>
          </h2>
          <dl className="metadata">
            <dt>记录</dt>
            <dd>正文、发生时间与首次录入时间</dd>
            <dt>日报</dt>
            <dd>事实正文、来源关联与版本</dd>
            <dt>修改历史</dt>
            <dd>原内容、修正内容和修改时间</dd>
            <dt>正式保存位置</dt>
            <dd>尚未确定</dd>
          </dl>
          <p className="subtle">
            这些是未来正式客户端的数据结构说明，不表示已保存到手机或电脑。
          </p>
        </section>
        <section className="info-block">
          <p className="eyebrow">02 / 另一端</p>
          <h2>
            <span className="mobile-copy">电脑与同步</span>
            <span className="desktop-copy">手机设备</span>
          </h2>
          <p className="status-label">同步尚未接入</p>
          <p>
            <span className="mobile-copy">
              此区域预留 Windows 接收端信息。没有真实配对设备，也没有传输记录。
            </span>
            <span className="desktop-copy">
              此区域预留安卓设备信息。没有真实配对设备，也没有传输记录。
            </span>
          </p>
          <p className="subtle">
            本机保存不等于已传到另一端。本框架不会扫描、连接或同步设备。
          </p>
        </section>
        <section className="info-block">
          <p className="eyebrow">03 / 当前演示</p>
          <h2>只读，不改动你的数据</h2>
          <p>
            所有记录和日报均为随页面提供的合成示例。不读取、写入或清空浏览器中的旧原型记录。
          </p>
          <p>
            刷新会重新展示同一组示例。日期选择与浏览位置不是正式业务数据，没有持久化承诺。
          </p>
        </section>
        <section className="info-block">
          <p className="eyebrow">04 / 数据边界</p>
          <h2>尚未提供备份与恢复</h2>
          <p>
            旧版原型曾使用浏览器存储；清除浏览器数据仍可能删除那些旧内容。当前框架不执行迁移、备份或恢复。
          </p>
          <p>
            未实现加密、云端保存、账号或正式安卓离线启动。请勿将此演示用作真实生活资料的保存工具。
          </p>
        </section>
      </div>
      <details className="history">
        <summary>后续客户端的职责</summary>
        <p>
          正式首版优先完成安卓记录与日报，以及 Windows 保存和局域网同步。Windows
          后续也将支持记录、编辑和生成日报，但本次只预留浏览框架。
        </p>
        <p>
          未来同步需分别证明本机保存、传输、接收及失败恢复；这里不以动画、进度或成功字样代替真实传输证据。
        </p>
      </details>
    </section>
  );
}
export default function App() {
  const [route, setRoute] = useState(routeFromHash);
  const positions = useRef(new Map<string, number>());
  const current = useRef(route.raw);
  useEffect(() => {
    const remember = () =>
      positions.current.set(current.current, window.scrollY);
    const click = (event: MouseEvent) => {
      if (
        event.target instanceof Element &&
        event.target.closest('a[href^="#"]')
      )
        remember();
    };
    const change = () => {
      const next = routeFromHash();
      current.current = next.raw;
      setRoute(next);
    };
    document.addEventListener("click", click, true);
    window.addEventListener("scroll", remember, { passive: true });
    window.addEventListener("hashchange", change);
    const restoration = history.scrollRestoration;
    history.scrollRestoration = "manual";
    return () => {
      document.removeEventListener("click", click, true);
      window.removeEventListener("scroll", remember);
      window.removeEventListener("hashchange", change);
      history.scrollRestoration = restoration;
    };
  }, []);
  useLayoutEffect(() => {
    const frame = requestAnimationFrame(() =>
      window.scrollTo(0, positions.current.get(route.raw) ?? 0),
    );
    return () => cancelAnimationFrame(frame);
  }, [route.raw]);
  const selectedRecord = entries.find((e) => e.id === route.id);
  const selectedReport = reports.find((r) => r.date === route.id);
  const filtered = route.date
    ? entries.filter((e) => e.occurred.startsWith(route.date))
    : entries;
  const dates = [...new Set(filtered.map((e) => e.occurred.slice(0, 10)))];
  return (
    <Shell section={route.section}>
      {route.section === "data" ? (
        <DataOverview />
      ) : route.section === "records" ? (
        <>
          <header className="page-heading">
            <p className="eyebrow">留下的事实</p>
            <h1>记录</h1>
            <p className="lede">按发生时间，回看那天的片段。</p>
          </header>
          <div
            className="browser-layout"
            data-view={route.id ? "detail" : "list"}
          >
            <section className="list-pane" aria-label="记录列表">
              {route.date && (
                <p className="notice">
                  {route.date} 的记录 · <a href="#records">查看全部日期</a>
                </p>
              )}
              {!filtered.length && (
                <Empty title="暂无记录">没有记录不代表没有行动。</Empty>
              )}
              {dates.map((date) => (
                <section className="list-group" key={date}>
                  <h2 className="section-heading">
                    {dateLabel(date)} <small>{date}</small>
                  </h2>
                  {filtered
                    .filter((e) => e.occurred.startsWith(date))
                    .map((e) => (
                      <a
                        className={`list-item ${selectedRecord?.id === e.id ? "selected" : ""}`}
                        key={e.id}
                        href={`#records/${e.id}${route.date ? `?date=${route.date}` : ""}`}
                        aria-current={
                          selectedRecord?.id === e.id ? "true" : undefined
                        }
                      >
                        <span className="item-time">
                          {e.occurred.slice(11)}
                        </span>
                        <span className="item-summary">{e.text}</span>
                        <span className="item-meta">
                          {e.created.slice(0, 10) !== date ? "补记" : ""}
                          {e.history.length
                            ? `${e.created.slice(0, 10) !== date ? " · " : ""}已修改`
                            : ""}
                        </span>
                      </a>
                    ))}
                </section>
              ))}
            </section>
            <section className="detail-pane">
              {route.id ? (
                <RecordDetail
                  entry={selectedRecord}
                  back={route.date ? `#records?date=${route.date}` : "#records"}
                  backLabel="返回记录列表"
                />
              ) : (
                <Empty title="选择一条记录">
                  阅读正文，核对发生与录入时间。
                </Empty>
              )}
            </section>
          </div>
        </>
      ) : (
        <>
          <header className="page-heading">
            <p className="eyebrow">一天的事实</p>
            <h1>日报</h1>
            <p className="lede">阅读、核对，不对一天作出评价。</p>
          </header>
          <div
            className="browser-layout"
            data-view={route.id ? "detail" : "list"}
          >
            <section className="list-pane" aria-label="日报列表">
              <label className="date-nav">
                定位日期
                <input
                  aria-label="定位日报日期"
                  type="date"
                  value={route.id ?? ""}
                  onChange={(e) => {
                    window.location.hash = e.target.value
                      ? `reports/${e.target.value}`
                      : "reports";
                  }}
                />
              </label>
              <p className="subtle">
                示例：10 月 30 日含修改版本；29 日含长文；27 日有记录无日报；22
                日为空。
              </p>
              {reports.map((r) => (
                <a
                  className={`list-item ${route.id === r.date ? "selected" : ""}`}
                  key={r.date}
                  href={`#reports/${r.date}`}
                  aria-current={route.id === r.date ? "true" : undefined}
                >
                  <span className="item-time">{dateLabel(r.date)}</span>
                  <span className="item-summary">
                    {r.items.map((item) => item.text).join(" ")}
                  </span>
                  <span className="item-meta">
                    {r.date}
                    {r.manual ? " · 有手动修改" : ""}
                    {r.versions.length ? " · 有历史版本" : ""}
                  </span>
                </a>
              ))}
            </section>
            <section className="detail-pane">
              {route.source ? (
                <RecordDetail
                  entry={entries.find((e) => e.id === route.source)}
                  back={`#reports/${route.id}`}
                  backLabel="返回日报"
                />
              ) : selectedReport ? (
                <ReportDetail
                  key={selectedReport.date}
                  report={selectedReport}
                />
              ) : route.id ? (
                <>
                  <a href="#reports" className="back-link">
                    ← 返回日报列表
                  </a>
                  <Empty
                    title={`${route.id} 暂无${entries.some((e) => e.occurred.startsWith(route.id)) ? "日报" : "记录和日报"}`}
                  >
                    {entries.some((e) => e.occurred.startsWith(route.id)) ? (
                      <a href={`#records?date=${route.id}`}>查看该日记录 →</a>
                    ) : (
                      "没有记录不代表没有行动；不编造活动或日报。"
                    )}
                  </Empty>
                </>
              ) : (
                <Empty title="选择一天的日报">
                  阅读事实正文，沿来源核对原始记录。
                </Empty>
              )}
            </section>
          </div>
        </>
      )}
    </Shell>
  );
}
