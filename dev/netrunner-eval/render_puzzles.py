#!/usr/bin/env python3
"""
Render Netrunner puzzle markdown files to HTML.
Usage: python render_puzzles.py
"""

import json
import re
from pathlib import Path

import yaml

# Paths
SCRIPT_DIR = Path(__file__).parent
PROBLEMS_DIR = SCRIPT_DIR / "problems"
HTML_DIR = SCRIPT_DIR / "html"
CARD_LOOKUP_FILE = SCRIPT_DIR / "card_lookup.json"
CARD_IMAGES_PATH = "../../../resources/public/img/cards/en/default/stock"

# Load card lookup
with open(CARD_LOOKUP_FILE) as f:
    CARD_LOOKUP = json.load(f)


def card_to_img(card_name: str) -> str:
    """Convert card name to <img> tag."""
    code = CARD_LOOKUP.get(card_name)
    if code:
        return f'<span class="card-ref"><img src="{CARD_IMAGES_PATH}/{code}.png" alt="{card_name}" title="{card_name}"><span class="card-name">{card_name}</span></span>'
    return f'<span class="card-missing">{card_name}</span>'


def replace_card_refs(text: str) -> str:
    """Replace [[Card Name]] with card images."""
    return re.sub(r'\[\[([^\]]+)\]\]', lambda m: card_to_img(m.group(1)), text)


def replace_icons(text: str) -> str:
    """Replace [credit], [Click] etc with styled spans."""
    text = re.sub(r'\[credit\]', '<span class="icon credit"></span>', text)
    text = re.sub(r'\[Click\]', '<span class="icon click"></span>', text)
    text = re.sub(r'\[mu\]', '<span class="icon mu"></span>', text)
    text = re.sub(r'\[link\]', '<span class="icon link"></span>', text)
    text = re.sub(r'\[trash\]', '<span class="icon trash"></span>', text)
    text = re.sub(r'\[recurring-credit\]', '<span class="icon recurring"></span>', text)
    return text


def parse_markdown_sections(content: str) -> dict:
    """Parse markdown into sections by ## headers."""
    sections = {}
    current_section = None
    current_content = []

    for line in content.split('\n'):
        if line.startswith('## '):
            if current_section:
                sections[current_section] = '\n'.join(current_content).strip()
            current_section = line[3:].strip()
            current_content = []
        elif line.startswith('# '):
            sections['_title'] = line[2:].strip()
        else:
            current_content.append(line)

    if current_section:
        sections[current_section] = '\n'.join(current_content).strip()

    return sections


def render_section(title: str, content: str) -> str:
    """Render a section to HTML."""
    # Handle code blocks
    content = re.sub(
        r'```\n?(.*?)\n?```',
        lambda m: f'<pre>{m.group(1)}</pre>',
        content,
        flags=re.DOTALL
    )

    # Bold text
    content = re.sub(r'\*\*([^*]+)\*\*', r'<strong>\1</strong>', content)

    # Replace card refs and icons
    content = replace_card_refs(content)
    content = replace_icons(content)

    # Convert subroutine arrows
    content = content.replace('↳', '<span class="subroutine">↳</span>')

    # Paragraphs
    content = re.sub(r'\n\n+', '</p><p>', content)
    content = f'<p>{content}</p>' if content else ''

    return f'''
    <section class="puzzle-section">
        <h2>{title}</h2>
        <div class="section-content">{content}</div>
    </section>'''


def render_answer(content: str) -> str:
    """Render answer markdown to HTML."""
    sections = parse_markdown_sections(content)
    html_parts = []

    for key, value in sections.items():
        if key.startswith('_'):
            continue
        html_parts.append(render_section(key, value))

    return '\n'.join(html_parts)


def parse_board_yaml(content: str) -> dict | None:
    """Extract and parse ```yaml board block from markdown."""
    match = re.search(r'```yaml\s*\n(.*?)```', content, re.DOTALL)
    if not match:
        return None
    try:
        return yaml.safe_load(match.group(1))
    except yaml.YAMLError:
        return None


def render_card_in_server(card_data: dict | str, show_face: bool = True) -> str:
    """Render a single card in a server context."""
    if isinstance(card_data, str):
        card_data = {'card': card_data}

    card_name = card_data.get('card', 'Unknown')
    rezzed = card_data.get('rezzed', False)
    faceup = card_data.get('faceup', rezzed)  # faceup defaults to rezzed state
    credits = card_data.get('credits')
    adv = card_data.get('adv')

    # Get card image
    code = CARD_LOOKUP.get(card_name)

    classes = ['board-card']
    if rezzed:
        classes.append('rezzed')
    if not faceup and show_face:
        classes.append('facedown')

    # Badge for credits/advancement
    badge = ''
    if credits is not None:
        badge = f'<span class="card-badge credits">${credits}</span>'
    elif adv is not None:
        badge = f'<span class="card-badge adv">{adv}▲</span>'

    if code and (faceup or not show_face):
        img = f'<img src="{CARD_IMAGES_PATH}/{code}.png" alt="{card_name}" title="{card_name}">'
    elif not faceup:
        # Show card back for unrezzed/facedown
        img = f'<div class="card-back" title="Unrezzed card"></div>'
    else:
        img = f'<div class="card-missing-board">{card_name}</div>'

    return f'<div class="{" ".join(classes)}">{img}{badge}</div>'


def render_ice_stack(ice_list: list) -> str:
    """Render a vertical ICE stack (outermost at top)."""
    if not ice_list:
        return ''

    html = '<div class="ice-stack">'
    for ice in ice_list:  # First is outermost (top)
        html += render_card_in_server(ice)
    html += '</div>'
    return html


def render_server(name: str, server_data: dict) -> str:
    """Render a single server column."""
    ice_html = render_ice_stack(server_data.get('ice', []))

    # Root cards (asset/agenda + upgrades in server)
    root = server_data.get('root')
    root_html = ''
    if root:
        if isinstance(root, list):
            # Multiple cards in root (e.g., asset + upgrade)
            cards_html = ''.join(render_card_in_server(c) for c in root)
            root_html = f'<div class="server-root">{cards_html}</div>'
        else:
            root_html = f'<div class="server-root">{render_card_in_server(root)}</div>'

    return f'''
    <div class="server">
        <div class="server-name">{name}</div>
        {ice_html}
        {root_html}
    </div>'''


def render_rig(rig_list: list) -> str:
    """Render runner's rig as a horizontal row."""
    if not rig_list:
        return '<div class="rig-empty">No installed cards</div>'

    html = '<div class="rig">'
    for card in rig_list:
        html += render_card_in_server(card, show_face=True)
    html += '</div>'
    return html


def render_board(board: dict) -> str:
    """Render full board state from YAML structure."""
    corp = board.get('corp', {})
    runner = board.get('runner', {})

    # Corp section
    corp_credits = corp.get('credits', 0)
    corp_points = corp.get('points', 0)
    corp_clicks = corp.get('clicks', 3)

    # Collect all servers
    servers_html = ''
    server_order = ['HQ', 'R&D', 'Archives']  # Centrals first

    # Add centrals
    for srv_name in server_order:
        if srv_name in corp:
            servers_html += render_server(srv_name, corp[srv_name])

    # Add remotes
    for key, val in corp.items():
        if key.startswith('Server') or key.startswith('Remote'):
            servers_html += render_server(key, val)

    # Runner section
    runner_credits = runner.get('credits', 0)
    runner_points = runner.get('points', 0)
    runner_clicks = runner.get('clicks', 4)
    grip_count = runner.get('grip', 0)
    if isinstance(grip_count, list):
        grip_count = len(grip_count)

    rig_html = render_rig(runner.get('rig', []))

    return f'''
    <section class="puzzle-section board-section">
        <h2>Board State</h2>
        <div class="board">
            <div class="corp-side">
                <div class="side-header">
                    <span class="side-label">Corp</span>
                    <span class="side-stats">${corp_credits} · {corp_points} pts · {corp_clicks} clicks</span>
                </div>
                <div class="servers">
                    {servers_html}
                </div>
            </div>
            <div class="runner-side">
                <div class="side-header">
                    <span class="side-label">Runner</span>
                    <span class="side-stats">${runner_credits} · {runner_points} pts · {runner_clicks} clicks · {grip_count} cards</span>
                </div>
                <div class="rig-container">
                    <div class="rig-label">Rig</div>
                    {rig_html}
                </div>
            </div>
        </div>
    </section>'''


def render_puzzle(q_file: Path) -> str:
    """Render a puzzle Q file (and its A file) to HTML."""

    # Read question file
    q_content = q_file.read_text()
    q_sections = parse_markdown_sections(q_content)

    # Read answer file
    a_file = q_file.with_name(q_file.name.replace('-q.md', '-a.md'))
    a_content = a_file.read_text() if a_file.exists() else ''

    # Extract title and difficulty
    title = q_sections.get('_title', q_file.stem)
    difficulty_match = re.search(r'\[(Easy|Medium|Hard)\]', title)
    difficulty = difficulty_match.group(1) if difficulty_match else 'Unknown'
    problem_name = re.sub(r'\s*\[.*?\]', '', title).replace('Problem: ', '')

    # Check for structured board YAML
    board_data = parse_board_yaml(q_content)

    # Build HTML sections in order, each section rendered only once
    html_sections = []
    rendered = set()

    # Situation/Context first
    for key in q_sections:
        if key in ('Situation', 'Context'):
            html_sections.append(render_section(key, q_sections[key]))
            rendered.add(key)
            break

    # Board state - use visual renderer if YAML present, else fall back to text
    if board_data:
        html_sections.append(render_board(board_data))
        rendered.add('Board State')
    else:
        # Fall back to text-based board state
        for key in q_sections:
            if key in rendered:
                continue
            if 'State' in key or 'Board' in key:
                html_sections.append(render_section(key, q_sections[key]))
                rendered.add(key)
                break

    # Hand section
    section_order = ['Hand', 'Card Text', 'Question']
    for section_prefix in section_order:
        for key in q_sections:
            if key in rendered:
                continue
            if key.startswith('_'):
                continue
            if key == section_prefix or key.startswith(section_prefix + ' ') or key.startswith(section_prefix + '('):
                html_sections.append(render_section(key, q_sections[key]))
                rendered.add(key)
                break

    # Render answer section
    answer_html = render_answer(a_content) if a_content else '<p>No answer file found.</p>'

    return HTML_TEMPLATE.format(
        title=problem_name,
        difficulty=difficulty,
        difficulty_class=difficulty.lower(),
        sections='\n'.join(html_sections),
        answer=answer_html
    )


HTML_TEMPLATE = '''<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>{title} - Netrunner Puzzle</title>
    <style>
        :root {{
            --bg-primary: #1a1a2e;
            --bg-secondary: #16213e;
            --bg-card: #0f0f23;
            --text-primary: #eee;
            --text-secondary: #aaa;
            --accent-corp: #c73e1d;
            --accent-runner: #3e8914;
            --border-color: #333;
        }}

        * {{ box-sizing: border-box; }}

        body {{
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: var(--bg-primary);
            color: var(--text-primary);
            line-height: 1.6;
            margin: 0;
            padding: 20px;
        }}

        .puzzle-container {{
            max-width: 900px;
            margin: 0 auto;
        }}

        header {{
            margin-bottom: 30px;
            padding-bottom: 20px;
            border-bottom: 1px solid var(--border-color);
        }}

        h1 {{
            margin: 0 0 10px 0;
            font-size: 1.8rem;
        }}

        .difficulty {{
            display: inline-block;
            padding: 4px 12px;
            border-radius: 4px;
            font-size: 0.85rem;
            font-weight: 600;
        }}

        .difficulty.easy {{ background: #2d5a27; color: #8fdf82; }}
        .difficulty.medium {{ background: #5a4a27; color: #dfcf82; }}
        .difficulty.hard {{ background: #5a2727; color: #df8282; }}

        .puzzle-section {{
            background: var(--bg-secondary);
            border-radius: 8px;
            padding: 20px;
            margin-bottom: 20px;
        }}

        .puzzle-section h2 {{
            margin: 0 0 15px 0;
            font-size: 1.2rem;
            color: var(--text-secondary);
            border-bottom: 1px solid var(--border-color);
            padding-bottom: 10px;
        }}

        pre {{
            background: var(--bg-card);
            padding: 15px;
            border-radius: 6px;
            overflow-x: auto;
            font-size: 0.9rem;
        }}

        /* Hand section: cards in a row */
        pre:has(.card-ref) {{
            display: flex;
            flex-wrap: wrap;
            gap: 12px;
            white-space: normal;
            overflow: visible;
        }}

        .card-ref {{
            display: inline-block;
            position: relative;
        }}

        .card-ref img {{
            height: 140px;
            border-radius: 6px;
            vertical-align: middle;
            cursor: pointer;
            transition: transform 0.2s ease;
        }}

        .card-ref:hover img {{
            transform: scale(2);
            z-index: 1000;
            position: relative;
            box-shadow: 0 8px 32px rgba(0,0,0,0.6);
        }}

        /* Ensure hover isn't clipped */
        .puzzle-section {{
            overflow: visible;
        }}

        .section-content {{
            overflow: visible;
        }}

        .card-ref .card-name {{
            display: none;
        }}

        .card-missing {{
            background: #3a2a2a;
            padding: 2px 8px;
            border-radius: 4px;
            font-style: italic;
        }}

        .icon {{
            display: inline-block;
            width: 1.2em;
            height: 1.2em;
            vertical-align: middle;
            background-size: contain;
            background-repeat: no-repeat;
        }}

        .icon.credit {{ background-color: #f0c040; border-radius: 50%; }}
        .icon.click {{ background-color: #4080f0; border-radius: 50%; }}
        .icon.mu {{ background-color: #40f080; border-radius: 50%; }}

        .subroutine {{
            color: #e08040;
            font-weight: bold;
        }}

        .answer-toggle {{
            background: var(--bg-secondary);
            border: 1px solid var(--border-color);
            color: var(--text-primary);
            padding: 12px 24px;
            border-radius: 6px;
            cursor: pointer;
            font-size: 1rem;
            margin: 20px 0;
            display: block;
            width: 100%;
        }}

        .answer-toggle:hover {{
            background: var(--bg-card);
        }}

        .answer-content {{
            display: none;
        }}

        .answer-content.visible {{
            display: block;
        }}

        .answer-container {{
            border-left: 4px solid var(--accent-runner);
            margin-top: 30px;
            padding-left: 20px;
        }}

        .back-link {{
            display: inline-block;
            margin-bottom: 20px;
            color: var(--text-secondary);
            text-decoration: none;
        }}

        .back-link:hover {{
            color: var(--text-primary);
        }}

        /* Board visualization */
        .board {{
            display: flex;
            flex-direction: column;
            gap: 30px;
        }}

        .corp-side {{
            border-left: 4px solid var(--accent-corp);
            padding-left: 15px;
        }}

        .runner-side {{
            border-left: 4px solid var(--accent-runner);
            padding-left: 15px;
        }}

        .side-header {{
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-bottom: 15px;
            padding-bottom: 10px;
            border-bottom: 1px solid var(--border-color);
        }}

        .side-label {{
            font-weight: 600;
            font-size: 1.1rem;
        }}

        .side-stats {{
            color: var(--text-secondary);
            font-size: 0.9rem;
        }}

        .servers {{
            display: flex;
            gap: 20px;
            flex-wrap: wrap;
            overflow: visible;
        }}

        .server {{
            display: flex;
            flex-direction: column;
            align-items: center;
            min-width: 100px;
        }}

        .server-name {{
            font-size: 0.85rem;
            color: var(--text-secondary);
            margin-bottom: 8px;
            font-weight: 500;
        }}

        .ice-stack {{
            display: flex;
            flex-direction: column;
            gap: 4px;
            margin-bottom: 8px;
        }}

        .server-root {{
            border-top: 2px dashed var(--border-color);
            padding-top: 8px;
        }}

        .board-card {{
            position: relative;
            transition: transform 0.2s ease;
        }}

        .board-card img {{
            height: 100px;
            border-radius: 4px;
            border: 2px solid transparent;
        }}

        .board-card.rezzed img {{
            border-color: var(--accent-corp);
        }}

        .board-card:hover {{
            transform: scale(1.8);
            z-index: 1000;
        }}

        .card-back {{
            width: 72px;
            height: 100px;
            background: linear-gradient(135deg, #2a2a4a 0%, #1a1a3a 100%);
            border: 2px solid #444;
            border-radius: 4px;
            display: flex;
            align-items: center;
            justify-content: center;
        }}

        .card-back::after {{
            content: "?";
            font-size: 2rem;
            color: #666;
        }}

        .card-badge {{
            position: absolute;
            bottom: 4px;
            right: 4px;
            padding: 2px 6px;
            border-radius: 4px;
            font-size: 0.75rem;
            font-weight: 600;
        }}

        .card-badge.credits {{
            background: #f0c040;
            color: #000;
        }}

        .card-badge.adv {{
            background: var(--accent-corp);
            color: #fff;
        }}

        .rig-container {{
            margin-top: 10px;
        }}

        .rig-label {{
            font-size: 0.85rem;
            color: var(--text-secondary);
            margin-bottom: 8px;
        }}

        .rig {{
            display: flex;
            gap: 10px;
            flex-wrap: wrap;
            overflow: visible;
        }}

        .rig-empty {{
            color: var(--text-secondary);
            font-style: italic;
        }}

        .card-missing-board {{
            width: 72px;
            height: 100px;
            background: #3a2a2a;
            border-radius: 4px;
            display: flex;
            align-items: center;
            justify-content: center;
            text-align: center;
            font-size: 0.7rem;
            padding: 4px;
        }}

        .board-section {{
            overflow: visible;
        }}
    </style>
</head>
<body>
    <div class="puzzle-container">
        <a href="index.html" class="back-link">← Back to puzzles</a>

        <header>
            <h1>{title}</h1>
            <span class="difficulty {difficulty_class}">{difficulty}</span>
        </header>

        {sections}

        <div class="answer-container">
            <button class="answer-toggle" onclick="toggleAnswer()">Show Answer</button>
            <div class="answer-content" id="answer">
                {answer}
            </div>
        </div>
    </div>

    <script>
        function toggleAnswer() {{
            const answer = document.getElementById('answer');
            const btn = document.querySelector('.answer-toggle');
            answer.classList.toggle('visible');
            btn.textContent = answer.classList.contains('visible') ? 'Hide Answer' : 'Show Answer';
        }}
    </script>
</body>
</html>
'''


def render_index(puzzles: list) -> str:
    """Render index page."""
    rows = []
    for p in sorted(puzzles, key=lambda x: x['name']):
        rows.append(f'''
            <tr>
                <td><a href="{p['filename']}">{p['name']}</a></td>
                <td><span class="difficulty {p['difficulty'].lower()}">{p['difficulty']}</span></td>
                <td>{p['side']}</td>
            </tr>''')

    return f'''<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Netrunner Puzzles</title>
    <style>
        :root {{
            --bg-primary: #1a1a2e;
            --bg-secondary: #16213e;
            --text-primary: #eee;
            --text-secondary: #aaa;
            --border-color: #333;
        }}

        body {{
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: var(--bg-primary);
            color: var(--text-primary);
            line-height: 1.6;
            margin: 0;
            padding: 40px 20px;
        }}

        .container {{
            max-width: 800px;
            margin: 0 auto;
        }}

        h1 {{
            margin-bottom: 30px;
        }}

        table {{
            width: 100%;
            border-collapse: collapse;
        }}

        th, td {{
            text-align: left;
            padding: 12px 15px;
            border-bottom: 1px solid var(--border-color);
        }}

        th {{
            color: var(--text-secondary);
            font-weight: 600;
        }}

        a {{
            color: #6eb5ff;
            text-decoration: none;
        }}

        a:hover {{
            text-decoration: underline;
        }}

        .difficulty {{
            display: inline-block;
            padding: 2px 10px;
            border-radius: 4px;
            font-size: 0.8rem;
        }}

        .difficulty.easy {{ background: #2d5a27; color: #8fdf82; }}
        .difficulty.medium {{ background: #5a4a27; color: #dfcf82; }}
        .difficulty.hard {{ background: #5a2727; color: #df8282; }}
    </style>
</head>
<body>
    <div class="container">
        <h1>Netrunner Puzzles</h1>
        <table>
            <thead>
                <tr>
                    <th>Puzzle</th>
                    <th>Difficulty</th>
                    <th>Side</th>
                </tr>
            </thead>
            <tbody>
                {''.join(rows)}
            </tbody>
        </table>
    </div>
</body>
</html>
'''


def main():
    # Ensure output directory exists
    HTML_DIR.mkdir(exist_ok=True)

    puzzles = []

    # Find all question files
    for q_file in sorted(PROBLEMS_DIR.glob('*-q.md')):
        name = q_file.stem.replace('-q', '')
        print(f"Rendering {name}...")

        # Extract difficulty and side from content
        content = q_file.read_text()
        diff_match = re.search(r'\[(Easy|Medium|Hard)\]', content)
        difficulty = diff_match.group(1) if diff_match else 'Unknown'
        side = 'Corp' if 'corp' in name else 'Runner'

        # Render and save
        html = render_puzzle(q_file)
        out_file = HTML_DIR / f"{name}.html"
        out_file.write_text(html)

        puzzles.append({
            'name': name,
            'filename': f"{name}.html",
            'difficulty': difficulty,
            'side': side
        })

    # Render index
    index_html = render_index(puzzles)
    (HTML_DIR / 'index.html').write_text(index_html)

    print(f"\nGenerated {len(puzzles)} puzzle pages + index")
    print(f"Open: {HTML_DIR / 'index.html'}")


if __name__ == '__main__':
    main()
