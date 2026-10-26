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
    f.render_widget(Paragraph::new(Line::from(app.header())), top);

    let view = app.view();
    draw_view(f, app, &view, body);
    f.render_widget(Paragraph::new(hint_line(app)), hint);

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

fn hint_line(app: &App) -> Line<'static> {
    if app.screen == Screen::Running && app.outcome.is_none() {
        return Line::from(vec![
            dim(" Working… "),
            key("L"),
            dim(" shows every command as it runs"),
        ]);
    }
    let mut v = vec![
        dim(" "),
        key("↑↓"),
        dim(" choose  "),
        key("Enter"),
        dim(" select  "),
    ];
    if matches!(
        app.screen,
        Screen::Cleanup | Screen::CleanupAll | Screen::Install | Screen::Undo
    ) {
        v.extend([key("Space"), dim(" tick  ")]);
    }
    if app.screen == Screen::CleanupAll {
        v.extend([key("/"), dim(" search  ")]);
    }
    v.extend([
        key("Esc"),
        dim(" back  "),
        key("L"),
        dim(" log  "),
        key("?"),
        dim(" help  "),
        key("Q"),
        dim(" quit   "),
    ]);
    v.push(dim("(the mouse works too)"));
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
    let body_h = if view.body.is_empty() {
        0
    } else {
        (view.body.len() as u16 + 1).min(inner.height.saturating_sub(3))
    };
    let gauge_h = if view.gauge.is_some() { 2 } else { 0 };
    let items_h: u16 = view.items.iter().map(item_height).sum();
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
    f.render_widget(
        Paragraph::new(view.body.clone()).wrap(Wrap { trim: false }),
        b,
    );
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

fn item_height(i: &Item) -> u16 {
    match &i.kind {
        Kind::Choice { desc, .. } | Kind::Check { desc, .. } if !desc.is_empty() => 2,
        Kind::Heading(t) if t.is_empty() => 1,
        Kind::Heading(_) => 2,
        _ => 1,
    }
}

fn draw_items(f: &mut Frame, app: &mut App, view: &View, area: Rect) {
    app.item_rects.clear();
    if area.height == 0 || view.items.is_empty() {
        return;
    }
    let focused = app.focused(&view.items);
    // Scroll so the selected item is visible.
    let heights: Vec<u16> = view.items.iter().map(item_height).collect();
    let mut start = 0;
    if let Some(fi) = focused {
        let mut used: u16 = heights[..=fi].iter().sum();
        while used > area.height && start < fi {
            used -= heights[start];
            start += 1;
        }
    }
    let mut y = area.y;
    for (i, item) in view.items.iter().enumerate().skip(start) {
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
        let sel = focused == Some(i);
        let marker = Span::styled(if sel { "▶ " } else { "  " }, Style::new().fg(ACCENT));
        let lines: Vec<Line> = match &item.kind {
            Kind::Choice { title, desc } => {
                let mut v = vec![Line::from(vec![
                    marker,
                    Span::styled(
                        format!(" {title} "),
                        if sel {
                            SELECTED
                        } else {
                            Style::new().add_modifier(Modifier::BOLD)
                        },
                    ),
                ])];
                if !desc.is_empty() {
                    v.push(Line::from(Span::styled(
                        format!("     {desc}"),
                        Style::new().fg(DIM),
                    )));
                }
                v
            }
            Kind::Check { on, title, desc } => {
                let mut v = vec![Line::from(vec![
                    marker,
                    Span::styled(
                        if *on { "[✓] " } else { "[ ] " },
                        Style::new()
                            .fg(if *on { OK } else { DIM })
                            .add_modifier(Modifier::BOLD),
                    ),
                    Span::styled(
                        format!("{title} "),
                        if sel { SELECTED } else { Style::new() },
                    ),
                ])];
                if !desc.is_empty() {
                    v.push(Line::from(Span::styled(
                        format!("        {desc}"),
                        Style::new().fg(DIM),
                    )));
                }
                v
            }
            Kind::Button(label) => vec![Line::from(vec![
                marker,
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
            Kind::Heading(t) => vec![
                Line::default(),
                Line::from(Span::styled(
                    t.clone(),
                    Style::new().fg(WARN).add_modifier(Modifier::BOLD),
                )),
            ],
        };
        f.render_widget(Paragraph::new(lines), rect);
        if item.selectable() {
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
