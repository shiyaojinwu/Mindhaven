import MarkdownIt from 'markdown-it';
import type { Citation } from '../../types/knowledge.js';

// Raw HTML stays text. Images are disabled to avoid automatic external requests.
const md = new MarkdownIt({ html: false, breaks: true, linkify: true }).disable('image');
md.inline.ruler.before('link', 'citation', (state, silent) => {
  if (state.linkLevel > 0 || state.src[state.pos] !== '[') return false;
  const match = /^\[([A-Za-z0-9][A-Za-z0-9_.:\-]*(?:\s*[,，、]\s*[A-Za-z0-9][A-Za-z0-9_.:\-]*)*)\](?!\()/.exec(state.src.slice(state.pos));
  if (!match) return false;
  const sources = (state.env.sources ?? []) as Citation[];
  const ids = match[1].split(/\s*[,，、]\s*/);
  if (!ids.some(id => sources.some(source => source.id === id))) return false;
  if (!silent) {
    for (const id of ids) {
      const index = sources.findIndex(source => source.id === id);
      const token = state.push(index < 0 ? 'text' : 'citation', '', 0);
      token.content = `[${id}]`;
      token.meta = { index, title: sources[index]?.title };
    }
  }
  state.pos += match[0].length;
  return true;
});
md.renderer.rules.citation = (tokens, index) => {
  const { index: sourceIndex, title } = tokens[index].meta as { index: number; title: string };
  const label = md.utils.escapeHtml(`查看来源 ${sourceIndex + 1}：${title}`);
  return `<button type="button" class="inline-source" data-source-index="${sourceIndex}" aria-label="${label}" title="${label}">[${sourceIndex + 1}]</button>`;
};
export function renderMarkdown(content: string, sources: Citation[] = []): string {
  return md.render(content, { sources });
}
