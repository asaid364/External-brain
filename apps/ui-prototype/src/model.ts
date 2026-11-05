// Static synthetic fixtures only. Never read or mutate the earlier prototype's storage.
export type RecordRevision = {
  modified: string;
  before: { text: string; occurred: string };
  after: { text: string; occurred: string };
};
export type Entry = {
  id: string;
  text: string;
  occurred: string;
  created: string;
  history: RecordRevision[];
};
export type ReportItem = { time: string; text: string; sources: string[] };
export type Report = {
  date: string;
  created: string;
  modified?: string;
  manual: boolean;
  items: ReportItem[];
  changedSources?: boolean;
  versions: { at: string; label: string; text: string }[];
};
const practiceBefore = "完成了一次合成练习，整理了一条问题。";
const practiceAfter =
  "完成了一次合成练习，并整理了两条问题。\n补充说明：第二条问题与步骤顺序有关，尚未再次验证。";
const longText = [
  "阅读合成材料时，先记下三个不熟悉的概念，再对照示例逐项查看。没有记录具体阅读时长。",
  "随后尝试用自己的话复述第一段：能描述示例的输入和输出，但对中间条件仍有疑问。本条只记录当时的情况，不据此判断掌握程度。",
  "练习中出现一处与示例不同的结果。将现象记在纸上后暂停，没有在当时继续排查，也没有确认原因。",
  "休息后重新阅读前面的说明，发现原文中有一个此前没有注意到的限制条件。是否与差异有关，尚未验证。",
  "计划下次先按原示例重做，再逐个改变条件。这是自由描述的计划，不代表已经执行，也不转换成待办。",
  "附记：这是一条用于验证中文长文本、换行与来源核对的合成记录。内容并非任何人的实际生活资料。",
].join("\n\n");
export const entries: Entry[] = [
  {
    id: "reading",
    occurred: "2026-10-30T21:10",
    created: "2026-10-30T21:16",
    text: "刚看完一段合成教程，还没有实践。计划明天做一个练习。",
    history: [],
  },
  {
    id: "practice",
    occurred: "2026-10-30T09:30",
    created: "2026-10-31T08:20",
    text: practiceAfter,
    history: [
      {
        modified: "2026-10-31T09:05",
        before: { text: practiceBefore, occurred: "2026-10-29T09:30" },
        after: { text: practiceAfter, occurred: "2026-10-30T09:30" },
      },
    ],
  },
  {
    id: "long-note",
    occurred: "2026-10-29T20:45",
    created: "2026-10-29T21:20",
    text: longText,
    history: [],
  },
  {
    id: "supplement",
    occurred: "2026-10-29T12:00",
    created: "2026-10-30T08:35",
    text: "补充合成事实：练习当时画了两张草图，未形成完整方案。发生时间为补充示例中的默认值，实际时刻未核实。",
    history: [],
  },
  ...Array.from({ length: 10 }, (_, i) => ({
    id: `sample-${i + 1}`,
    occurred: `2026-10-${String(28 - Math.floor(i / 2)).padStart(2, "0")}T${i % 2 ? "09:15" : "18:40"}`,
    created: `2026-10-${String(28 - Math.floor(i / 2)).padStart(2, "0")}T19:00`,
    text:
      i % 2
        ? "整理合成示例中的材料，保留了原文和两段摘录。没有记录后续结果。"
        : "合成阅读记录：对照示例查看步骤，遇到不清楚的部分暂时记下。计划稍后核对，尚未记录是否执行。",
    history: [],
  })),
].sort(
  (a, b) => b.occurred.localeCompare(a.occurred) || a.id.localeCompare(b.id),
);
export const reports: Report[] = [
  {
    date: "2026-10-30",
    created: "2026-10-30T22:00",
    modified: "2026-10-31T08:00",
    manual: true,
    changedSources: true,
    items: [
      { time: "09:30", text: practiceBefore, sources: ["practice"] },
      {
        time: "21:10",
        text: "阅读了一段合成教程，尚未实践；原记录提到计划次日做一个练习。",
        sources: ["reading"],
      },
    ],
    versions: [
      {
        at: "2026-10-30T22:00",
        label: "整理稿示例",
        text: "09:30　完成了一次合成练习。\n\n21:10　阅读合成教程，尚未实践，提到次日练习计划。",
      },
    ],
  },
  {
    date: "2026-10-29",
    created: "2026-10-29T22:00",
    manual: false,
    items: [
      {
        time: "12:00",
        text: "补充记录提到当时画了两张草图，未形成完整方案；该条发生时刻尚未核实。",
        sources: ["supplement"],
      },
      { time: "20:45", text: longText, sources: ["long-note", "supplement"] },
      {
        time: "时间未核实",
        text: "此条合成日报保留了一个暂不可查看的来源，用于验证缺失来源时不编造原文。",
        sources: ["unavailable"],
      },
    ],
    versions: [],
  },
  {
    date: "2026-10-28",
    created: "2026-10-28T22:00",
    manual: false,
    items: [
      {
        time: "09:15",
        text: "整理合成材料，保留原文及摘录；未记录后续结果。",
        sources: ["sample-2"],
      },
      {
        time: "18:40",
        text: "对照示例查看步骤，记下疑问；提到稍后核对的计划，未记录是否执行。",
        sources: ["sample-1"],
      },
    ],
    versions: [],
  },
];
export function dateLabel(date: string) {
  const parsed = new Date(`${date}T12:00:00`);
  return Number.isNaN(parsed.valueOf())
    ? date
    : new Intl.DateTimeFormat("zh-CN", {
        month: "long",
        day: "numeric",
        weekday: "long",
      }).format(parsed);
}
export function timeLabel(value: string) {
  return value.replace("T", " ");
}
