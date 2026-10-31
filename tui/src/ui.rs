//! Drawing: a status line, the current screen (text + choices), a hint line; the log and pop-ups
//! on top. Every screen is drawn the same way from screens.rs's View.

use crate::app::{App, Input, Modal, Screen};
use crate::log::Level;
use crate::screens::{ACCENT, BAD, DIM, Item, Kind, OK, View, WARN};
use ratatui::Frame;
use ratatui::layout::{Constraint, Layout, Margin, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span, Text};
use ratatui::widgets::{Block, Borders, Clear, Gauge, Padding, Paragraph, Wrap};

const SELECTED: Style = Style::new()
    .fg(Color::Black)
    .bg(ACCENT)
    .add_modifier(Modifier::BOLD);

pub fn draw(f: &mut Frame, app: &mut App) {
    let area = f.area();
    let [top, body, hint] = Layout::vertical([
        Constraint::Length(1),
        Constraint::Min(5),
        Constraint::Length(1),
    ])
    .areas(area);
    f.render_widget(Paragraph::new(Line::from(app.header(top.width))), top);

    let view = app.view();
    draw_view(f, app, &view, body);
    f.render_widget(Paragraph::new(hint_line(app, hint.width)), hint);

    if app.show_log {
        draw_log(f, app, area.inner(Margin::new(2, 1)));
    }
    draw_modal(f, app, area);
    plain(f, app.ascii, app.no_color);
}

/// Old consoles (Windows' classic console) have no glyphs for ✓ ⟳ ▶ and friends, and some
/// people want no colour (NO_COLOR): rewrite the finished screen instead of every screen.
fn plain(f: &mut Frame, ascii: bool, no_color: bool) {
    if !ascii && !no_color {
        return;
    }
    for cell in f.buffer_mut().content.iter_mut() {
        if ascii && let Some(a) = ascii_for(cell.symbol()) {
            cell.set_symbol(a);
        }
        if no_color {
            // Highlights keep showing as reversed text.
            if cell.bg != Color::Reset {
                cell.modifier.insert(Modifier::REVERSED);
            }
            cell.fg = Color::Reset;
            cell.bg = Color::Reset;
        }
    }
}

/// A one-column ASCII stand-in for the symbols this app draws.
pub fn ascii_for(sym: &str) -> Option<&'static str> {
    Some(match sym {
        "✓" | "●" | "•" => "*",
        "✗" => "X",
        "○" => "o",
        "▶" | "→" => ">",
        "←" => "<",
        "↑" => "^",
        "↓" => "v",
        "⚠" => "!",
        "⟳" => "~",
        "…" | "·" => ".",
        "–" | "—" | "─" | "━" | "═" => "-",
        "│" | "┃" | "║" | "▏" => "|",
        "┌" | "┐" | "└" | "┘" | "╭" | "╮" | "╰" | "╯" | "├" | "┤" | "┬" | "┴" | "┼" => {
            "+"
        }
        "█" | "▉" | "▊" | "▋" | "▌" | "▍" | "▎" => "#",
        _ => return None,
    })
}

fn key(s: &str) -> Span<'static> {
    Span::styled(
        s.to_string(),
        Style::new().fg(ACCENT).add_modifier(Modifier::BOLD),
    )
}

fn dim(s: &str) -> Span<'static> {
    Span::styled(s.to_string(), Style::new().fg(DIM))
}

fn hint_line(app: &App, width: u16) -> Line<'static> {
    // A phone (Termux) in portrait: the same keys, fewer words.
    let narrow = width < 80;
    if app.screen == Screen::Running && app.outcome.is_none() {
        return Line::from(vec![
            dim(" Working… "),
            key("L"),
            dim(" shows every command as it runs"),
        ]);
    }
    let gap = if narrow { " " } else { "  " };
    let word = |w: &'static str| if narrow { gap } else { w };
    let mut v = vec![
        dim(" "),
        key("↑↓"),
        dim(word(" choose  ")),
        key("Enter"),
        dim(word(" select  ")),
    ];
    if matches!(
        app.screen,
        Screen::Cleanup | Screen::CleanupAll | Screen::Install | Screen::Undo
    ) {
        v.extend([key("Space"), dim(word(" tick  "))]);
    }
    if app.screen == Screen::CleanupAll {
        v.extend([key("/"), dim(word(" search  "))]);
    }
    v.extend([
        key("Esc"),
        dim(word(" back  ")),
        key("L"),
        dim(if narrow { " log " } else { " log  " }),
        key("?"),
        dim(if narrow { " help " } else { " help  " }),
        key("Q"),
        dim(" quit   "),
    ]);
    if !narrow {
        v.push(dim("(the mouse works too)"));
    }
    Line::from(v)
}

fn draw_view(f: &mut Frame, app: &mut App, view: &View, area: Rect) {
    let outer = Block::bordered()
        .title(Span::styled(
            format!(" {} ", view.title),
            Style::new().fg(ACCENT).add_modifier(Modifier::BOLD),
        ))
        .border_style(Style::new().fg(DIM));
    let inner = outer.inner(area).inner(Margin::new(2, 1));
    f.render_widget(outer, area);

    // Text, then a progress bar, then the choices, then extra lines (steps, commands, logcat).
    // The text's height counts wrapped lines: on a phone (Termux) most lines wrap.
    let body_lines = wrap_body(&view.body, inner.width);
    let body = Paragraph::new(body_lines.clone()).wrap(Wrap { trim: false });
    let body_need = if view.body.is_empty() {
        0
    } else {
        body.line_count(inner.width) as u16 + 1
    };
    let gauge_h = if view.gauge.is_some() { 2 } else { 0 };
    let items_h: u16 = view
        .items
        .iter()
        .map(|i| item_lines(i, false, inner.width).len() as u16)
        .sum();
    // Too tall for the screen (a phone): text and choices scroll together, so nothing is cut off.
    app.flow_active = false;
    if view.after.is_empty() && view.gauge.is_none() && body_need + items_h > inner.height {
        draw_flow(f, app, view, &body_lines, inner);
        return;
    }
    let body_h = body_need.min(inner.height.saturating_sub(3));
    let rest = inner.height.saturating_sub(body_h + gauge_h);
    let items_area_h = if view.after.is_empty() {
        rest
    } else {
        items_h.min(rest)
    };
    let [b, g, it, af] = Layout::vertical([
        Constraint::Length(body_h),
        Constraint::Length(gauge_h),
        Constraint::Length(items_area_h),
        Constraint::Min(0),
    ])
    .areas(inner);
    f.render_widget(body, b);
    if let Some((ratio, label)) = &view.gauge {
        f.render_widget(
            Gauge::default()
                .gauge_style(Style::new().fg(ACCENT).bg(Color::Indexed(236)))
                .ratio(ratio.clamp(0.0, 1.0))
                .label(label.clone()),
            Rect { height: 1, ..g },
        );
    }
    draw_items(f, app, view, it);
    if !view.after.is_empty() && af.height > 1 {
        let visible = af.height - 1;
        let max = (view.after.len() as u16).saturating_sub(visible);
        // Running steps and the live log follow the newest line; command lists start at the top.
        let scroll = if matches!(app.screen, Screen::Running | Screen::Logcat) {
            max.saturating_sub(app.after_scroll.min(max))
        } else {
            app.after_scroll.min(max)
        };
        f.render_widget(
            Paragraph::new(view.after.clone())
                .scroll((scroll, 0))
                .block(
                    Block::new()
                        .borders(Borders::TOP)
                        .border_style(Style::new().fg(DIM)),
                ),
            af,
        );
    }
}

/// Words of `text` in lines no wider than `width`: the first starts with `first`, the rest with
/// `rest` (a hanging indent). A word longer than a line (a folder path) is split across lines.
pub fn wrap_words(text: &str, width: u16, first: &str, rest: &str) -> Vec<String> {
    let w = |s: &str| Span::raw(s.to_string()).width();
    let width = width.max(8) as usize;
    let room = width.saturating_sub(w(rest)).max(4);
    let mut words: Vec<String> = Vec::new();
    for word in text.split(' ').filter(|x| !x.is_empty()) {
        let chars: Vec<char> = word.chars().collect();
        if w(word) <= room {
            words.push(word.to_string());
        } else {
            words.extend(chars.chunks(room).map(|c| c.iter().collect::<String>()));
        }
    }
    let mut out = Vec::new();
    let mut line = first.to_string();
    let mut empty = true;
    for word in &words {
        let candidate = if empty {
            format!("{line}{word}")
        } else {
            format!("{line} {word}")
        };
        if w(&candidate) <= width || empty {
            line = candidate;
            empty = false;
        } else {
            out.push(line);
            line = format!("{rest}{word}");
        }
    }
    out.push(line);
    out
}

/// Leading spaces plus a bullet (`• `, `- `, `1. `): wrapped lines line up under the text.
fn hanging_indent(text: &str) -> (String, String, String) {
    let lead = text.len() - text.trim_start().len();
    let (pad, after) = text.split_at(lead);
    let marker_len = ["• ", "- ", "⚠  "]
        .iter()
        .find(|m| after.starts_with(**m))
        .map(|m| m.len())
        .or_else(|| {
            let digits = after.chars().take_while(|c| c.is_ascii_digit()).count();
            (digits > 0 && after[digits..].starts_with(". ")).then_some(digits + 2)
        })
        .unwrap_or(0);
    let (marker, words) = after.split_at(marker_len);
    let first = format!("{pad}{marker}");
    let rest = " ".repeat(Span::raw(first.clone()).width());
    (first, rest, words.to_string())
}

/// The text above the choices, wrapped with hanging indents. Lines made of several styled
/// pieces are left whole (the paragraph wraps them).
fn wrap_body(body: &[Line<'static>], width: u16) -> Vec<Line<'static>> {
    let mut out = Vec::new();
    for l in body {
        if l.spans.len() != 1 || l.width() <= width as usize {
            out.push(l.clone());
            continue;
        }
        let span = &l.spans[0];
        let (first, rest, words) = hanging_indent(&span.content);
        for piece in wrap_words(&words, width, &first, &rest) {
            out.push(Line::from(Span::styled(piece, span.style)).style(l.style));
        }
    }
    out
}

/// One choice as lines, its title and description wrapped to `width`.
fn item_lines(item: &Item, sel: bool, width: u16) -> Vec<Line<'static>> {
    let marker = || Span::styled(if sel { "▶ " } else { "  " }, Style::new().fg(ACCENT));
    let desc_lines = |desc: &str, indent: &str| -> Vec<Line<'static>> {
        if desc.is_empty() {
            return Vec::new();
        }
        wrap_words(desc, width, indent, indent)
            .into_iter()
            .map(|d| Line::from(Span::styled(d, Style::new().fg(DIM))))
            .collect()
    };
    match &item.kind {
        Kind::Choice { title, desc } => {
            let style = if sel {
                SELECTED
            } else {
                Style::new().add_modifier(Modifier::BOLD)
            };
            let mut v: Vec<Line> = wrap_words(title, width.saturating_sub(4), "", "")
                .into_iter()
                .enumerate()
                .map(|(n, t)| {
                    Line::from(vec![
                        if n == 0 { marker() } else { Span::raw("  ") },
                        Span::styled(format!(" {t} "), style),
                    ])
                })
                .collect();
            v.extend(desc_lines(desc, "     "));
            v
        }
        Kind::Check { on, title, desc } => {
            let style = if sel { SELECTED } else { Style::new() };
            let mut v: Vec<Line> = wrap_words(title, width.saturating_sub(7), "", "")
                .into_iter()
                .enumerate()
                .map(|(n, t)| {
                    Line::from(vec![
                        if n == 0 { marker() } else { Span::raw("  ") },
                        if n == 0 {
                            Span::styled(
                                if *on { "[✓] " } else { "[ ] " },
                                Style::new()
                                    .fg(if *on { OK } else { DIM })
                                    .add_modifier(Modifier::BOLD),
                            )
                        } else {
                            Span::raw("    ")
                        },
                        Span::styled(format!("{t} "), style),
                    ])
                })
                .collect();
            v.extend(desc_lines(desc, "        "));
            v
        }
        Kind::Button(label) => vec![Line::from(vec![
            marker(),
            Span::styled(
                format!("  {label}  "),
                if sel {
                    SELECTED
                } else {
                    Style::new()
                        .fg(Color::White)
                        .bg(Color::Indexed(238))
                        .add_modifier(Modifier::BOLD)
                },
            ),
        ])],
        Kind::Heading(t) if t.is_empty() => vec![Line::default()],
        Kind::Heading(t) => std::iter::once(Line::default())
            .chain(wrap_words(t, width, "", "").into_iter().map(|h| {
                Line::from(Span::styled(
                    h,
                    Style::new().fg(WARN).add_modifier(Modifier::BOLD),
                ))
            }))
            .collect(),
    }
}

/// The text and the choices as one column that scrolls to keep the selected choice in view.
/// ↑ on the first choice scrolls the text above it back into view (`app.flow_up`).
fn draw_flow(f: &mut Frame, app: &mut App, view: &View, body: &[Line<'static>], area: Rect) {
    app.item_rects.clear();
    app.flow_active = true;
    let focused = app.focused(&view.items);
    // (lines, height, item index), top to bottom.
    let mut chunks: Vec<(Vec<Line>, u16, Option<usize>)> = body
        .iter()
        .map(|l| {
            let h = Paragraph::new(l.clone())
                .wrap(Wrap { trim: false })
                .line_count(area.width) as u16;
            (vec![l.clone()], h.max(1), None)
        })
        .collect();
    if !body.is_empty() {
        chunks.push((vec![Line::default()], 1, None));
    }
    for (i, item) in view.items.iter().enumerate() {
        let lines = item_lines(item, focused == Some(i), area.width);
        let h = lines.len() as u16;
        chunks.push((lines, h, Some(i)));
    }
    // Scrolled just enough for the selected choice to show, less what ↑ scrolled back.
    let mut top = 0u16;
    let mut auto = 0u16;
    for c in &chunks {
        if c.2.is_some() && c.2 == focused {
            auto = (top + c.1).saturating_sub(area.height);
        }
        top += c.1;
    }
    app.flow_up = app.flow_up.min(auto);
    let offset = auto - app.flow_up;
    // Markers in the empty rows just above and below: there's more to see.
    let mark = |f: &mut Frame, y: u16, text: &'static str| {
        let w = text.chars().count() as u16;
        let rect = Rect {
            x: area.x + area.width.saturating_sub(w),
            y,
            width: w.min(area.width),
            height: 1,
        };
        f.render_widget(
            Paragraph::new(Span::styled(text, Style::new().fg(DIM))),
            rect,
        );
    };
    if offset > 0 && area.y > 0 {
        mark(f, area.y - 1, "↑ more");
    }
    if top > offset + area.height {
        mark(f, area.y + area.height, "↓ more");
    }
    let mut t = 0u16;
    for (lines, h, idx) in chunks {
        let (from, to) = (t.max(offset), (t + h).min(offset + area.height));
        if from < to {
            let rect = Rect {
                x: area.x,
                y: area.y + from - offset,
                width: area.width,
                height: to - from,
            };
            f.render_widget(
                Paragraph::new(lines)
                    .wrap(Wrap { trim: false })
                    .scroll((from - t, 0)),
                rect,
            );
            if let Some(i) = idx
                && view.items[i].selectable()
            {
                app.item_rects.push((i, rect));
            }
        }
        t += h;
    }
}

fn draw_items(f: &mut Frame, app: &mut App, view: &View, area: Rect) {
    app.item_rects.clear();
    if area.height == 0 || view.items.is_empty() {
        return;
    }
    let focused = app.focused(&view.items);
    let all: Vec<Vec<Line>> = view
        .items
        .iter()
        .enumerate()
        .map(|(i, item)| item_lines(item, focused == Some(i), area.width))
        .collect();
    // Scroll so the selected item is visible.
    let heights: Vec<u16> = all.iter().map(|l| l.len() as u16).collect();
    let mut start = 0;
    if let Some(fi) = focused {
        let mut used: u16 = heights[..=fi].iter().sum();
        while used > area.height && start < fi {
            used -= heights[start];
            start += 1;
        }
    }
    let mut y = area.y;
    for (i, lines) in all.into_iter().enumerate().skip(start) {
        let h = heights[i];
        if y + h > area.y + area.height {
            break;
        }
        let rect = Rect {
            x: area.x,
            y,
            width: area.width,
            height: h,
        };
        f.render_widget(Paragraph::new(lines), rect);
        if view.items[i].selectable() {
            app.item_rects.push((i, rect));
        }
        y += h;
    }
}

fn draw_log(f: &mut Frame, app: &App, area: Rect) {
    f.render_widget(Clear, area);
    let entries = app.log.entries(app.problems_only);
    let inner_h = area.height.saturating_sub(2) as usize;
    let end = entries
        .len()
        .saturating_sub(app.log_scroll.min(entries.len()));
    let start = end.saturating_sub(inner_h);
    let mut lines: Vec<Line> = entries[start..end]
        .iter()
        .map(|e| {
            let c = match e.level {
                Level::Cmd => ACCENT,
                Level::Out if e.is_problem() => BAD,
                Level::Out => Color::Gray,
                Level::Info => Color::Reset,
                Level::Warn => WARN,
                Level::Error => BAD,
            };
            let indent = if e.level == Level::Out { "    " } else { "" };
            Line::from(vec![
                Span::styled(format!("{} ", e.time), Style::new().fg(DIM)),
                Span::styled(format!("{indent}{}", e.text), Style::new().fg(c)),
            ])
        })
        .collect();
    if lines.is_empty() {
        lines.push(Line::from(dim(if app.problems_only {
            "No problems so far."
        } else {
            "Nothing yet."
        })));
    }
    let n = app.log.problem_count();
    let title = format!(
        " {} · {} problem{} · E {} · ↑↓ PgUp PgDn scroll · Esc close ",
        if app.problems_only {
            "Problems only"
        } else {
            "Everything this app did"
        },
        n,
        if n == 1 { "" } else { "s" },
        if app.problems_only {
            "show everything"
        } else {
            "problems only"
        }
    );
    f.render_widget(
        Paragraph::new(lines).block(
            Block::bordered()
                .title(Span::styled(
                    title,
                    Style::new().fg(ACCENT).add_modifier(Modifier::BOLD),
                ))
                .border_style(Style::new().fg(ACCENT)),
        ),
        area,
    );
}

fn draw_modal(f: &mut Frame, app: &App, area: Rect) {
    let Some(m) = &app.modal else { return };
    let width = Constraint::Length(area.width.saturating_sub(8).min(84));
    match m {
        Modal::Notice(text) => {
            let wrapped: u16 = text
                .lines()
                .map(|l| (l.chars().count() as u16 / 78) + 1)
                .sum();
            let r = area.centered(width, Constraint::Length((wrapped + 5).min(area.height)));
            f.render_widget(Clear, r);
            let mut t = Text::from(text.clone());
            t.push_line(Line::default());
            t.push_line(Line::from(dim("Press any key (or click) to continue")));
            f.render_widget(
                Paragraph::new(t).wrap(Wrap { trim: false }).block(
                    Block::bordered()
                        .border_style(Style::new().fg(WARN))
                        .padding(Padding::horizontal(1)),
                ),
                r,
            );
        }
        Modal::Input {
            purpose,
            title,
            hint,
            value,
        } => {
            let r = area.centered(width, Constraint::Length(10));
            f.render_widget(Clear, r);
            let example = match purpose {
                Input::Address => "192.168.1.50",
                Input::PairAddress => "192.168.1.50:37123",
                Input::PairCode(_) => "123456",
                Input::PairConnect => "192.168.1.50:41235",
                Input::ApkPath => "",
                Input::PowerMac(_) => "",
            };
            let t = vec![
                Line::from(dim(hint)),
                Line::default(),
                Line::from(vec![
                    Span::styled(
                        format!(" {value}▏ "),
                        Style::new()
                            .fg(Color::White)
                            .bg(Color::Indexed(236))
                            .add_modifier(Modifier::BOLD),
                    ),
                    dim(&if value.is_empty() && !example.is_empty() {
                        format!("  e.g. {example}")
                    } else {
                        String::new()
                    }),
                ]),
                Line::default(),
                Line::from(vec![
                    key("Enter"),
                    dim(" OK    "),
                    key("Esc"),
                    dim(" cancel"),
                ]),
            ];
            f.render_widget(
                Paragraph::new(t).wrap(Wrap { trim: false }).block(
                    Block::bordered()
                        .title(Span::styled(
                            format!(" {title} "),
                            Style::new().fg(ACCENT).add_modifier(Modifier::BOLD),
                        ))
                        .border_style(Style::new().fg(ACCENT))
                        .padding(Padding::horizontal(1)),
                ),
                r,
            );
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn words_wrap_with_a_hanging_indent() {
        let (first, rest, words) = hanging_indent("  • turns off 5 apps that show ads");
        assert_eq!((first.as_str(), rest.as_str()), ("  • ", "    "));
        assert_eq!(
            wrap_words(&words, 20, &first, &rest),
            vec!["  • turns off 5 apps", "    that show ads"]
        );
        let (first, rest, _) = hanging_indent("  12. Note the address");
        assert_eq!((first.as_str(), rest.as_str()), ("  12. ", "      "));
        // A word longer than a line (a folder path) is split, nothing is cut off.
        let path = "/data/data/com.termux/files/home/storage/downloads";
        let lines = wrap_words(path, 20, "  ", "  ");
        assert!(lines.iter().all(|l| l.chars().count() <= 20), "{lines:?}");
        assert_eq!(lines.concat().replace(' ', ""), path);
    }

    #[test]
    fn every_symbol_the_screens_use_has_an_ascii_stand_in() {
        for src in [
            include_str!("screens.rs"),
            include_str!("ui.rs"),
            include_str!("app.rs"),
        ] {
            for c in src.chars().filter(|c| !c.is_ascii()) {
                // Text in comments and the stand-in table itself is fine; what reaches the
                // screen is either in the table or plain Latin text.
                if matches!(c, 'é' | 'ü') {
                    continue;
                }
                assert!(
                    ascii_for(c.encode_utf8(&mut [0; 4])).is_some(),
                    "no ASCII stand-in for {c:?} (U+{:04X})",
                    c as u32
                );
            }
        }
    }
}
