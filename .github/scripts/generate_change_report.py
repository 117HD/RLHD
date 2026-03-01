#!/usr/bin/env python3
"""
Compare old and new gamevals.json files and generate a change report.
Detects renamed (same ID, different name), added, and removed gamevals.
"""
import json
import subprocess
import sys
from pathlib import Path
from typing import Any, Dict, List, Tuple, Union

GAMEVALS_PATH = Path('src/main/resources/rs117/hd/scene/gamevals.json')


def find_json_files() -> List[tuple]:
    """Discover all JSON files in the project, excluding gamevals.json."""
    root = Path('.')
    result = []
    for p in sorted(root.rglob('*.json')):
        if p.name == 'gamevals.json':
            continue
        result.append((p, p.name))
    return result


def load_gamevals(file_path: Path) -> Dict[str, Dict[str, int]]:
    """Load gamevals.json file and parse JSON content."""
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()
        if content.startswith('//'):
            content = '\n'.join(content.split('\n')[1:])
        return json.loads(content)



def check_json_files_for_gamevals(
    removed_names: Dict[str, List[Tuple[str, int]]],
    renamed_old_names: Dict[str, List[Tuple[str, str, int]]]
) -> Dict[str, Dict[str, Dict[str, Union[List[int], str]]]]:
    """Check JSON files for removed/renamed gameval names and return line numbers."""
    names_to_check: Dict[str, str] = {}
    for removed_list in removed_names.values():
        for name, _ in removed_list:
            names_to_check[name] = 'removed'
    for renamed_list in renamed_old_names.values():
        for old_name, _, _ in renamed_list:
            names_to_check[old_name] = 'renamed'
    
    if not names_to_check:
        return {}
    
    affected_files: Dict[str, Dict[str, Dict[str, Union[List[int], str]]]] = {}

    for json_path, file_display_name in find_json_files():
        if not json_path.exists():
            continue
        
        try:
            with open(json_path, 'r', encoding='utf-8') as f:
                file_lines = f.readlines()
            
            file_matches: Dict[str, Dict[str, Union[List[int], str]]] = {}
            for gameval_name, change_type in names_to_check.items():
                line_numbers = [
                    line_num
                    for line_num, line in enumerate(file_lines, start=1)
                    if f'"{gameval_name}"' in line
                ]
                
                if line_numbers:
                    file_matches[gameval_name] = {
                        'line_numbers': line_numbers,
                        'change_type': change_type
                    }
            
            if file_matches:
                affected_files[file_display_name] = file_matches
        except Exception as e:
            print(f"Warning: Could not check {file_display_name}: {e}", file=sys.stderr)
    
    return affected_files

def compare_gamevals(
    old_gamevals: Dict[str, Dict[str, int]],
    new_gamevals: Dict[str, Dict[str, int]]
) -> Dict[str, Dict[str, List[Tuple[Any, ...]]]]:
    """Compare old and new gamevals and detect changes."""
    changes: Dict[str, Dict[str, List[Tuple[Any, ...]]]] = {
        'renamed': {},
        'added': {},
        'removed': {}
    }
    
    all_categories = set(old_gamevals.keys()) | set(new_gamevals.keys())
    
    for category in all_categories:
        old_constants = old_gamevals.get(category, {})
        new_constants = new_gamevals.get(category, {})
        
        old_id_to_names: Dict[int, List[str]] = {}
        for name, id_val in old_constants.items():
            old_id_to_names.setdefault(id_val, []).append(name)
        
        new_id_to_names: Dict[int, List[str]] = {}
        for name, id_val in new_constants.items():
            new_id_to_names.setdefault(id_val, []).append(name)
        
        processed_ids = set()
        
        common_ids = set(old_id_to_names.keys()) & set(new_id_to_names.keys())
        for id_val in common_ids:
            old_names = set(old_id_to_names[id_val])
            new_names = set(new_id_to_names[id_val])
            
            if old_names != new_names:
                processed_ids.add(id_val)
                if category not in changes['renamed']:
                    changes['renamed'][category] = []
                for old_name in old_names - new_names:
                    for new_name in new_names - old_names:
                        changes['renamed'][category].append((old_name, new_name, id_val))
        
        removed_ids = set(old_id_to_names.keys()) - set(new_id_to_names.keys())
        for id_val in removed_ids:
            if id_val not in processed_ids:
                if category not in changes['removed']:
                    changes['removed'][category] = []
                for old_name in old_id_to_names[id_val]:
                    changes['removed'][category].append((old_name, id_val))
        
        added_ids = set(new_id_to_names.keys()) - set(old_id_to_names.keys())
        for id_val in added_ids:
            if category not in changes['added']:
                changes['added'][category] = []
            for new_name in new_id_to_names[id_val]:
                changes['added'][category].append((new_name, id_val))
        
        for old_name, old_id in old_constants.items():
            if old_name in new_constants:
                new_id = new_constants[old_name]
                if old_id != new_id:
                    changes['removed'].setdefault(category, []).append((old_name, old_id))
                    changes['added'].setdefault(category, []).append((old_name, new_id))
    
    return changes

def generate_report(changes: Dict[str, Dict[str, List[Tuple[Any, ...]]]]) -> str:
    """Generate a markdown report of changes."""
    report_lines: List[str] = []
    
    affected_files = check_json_files_for_gamevals(changes['removed'], changes['renamed'])
    
    if affected_files:
        # Summary at top for potentially breaking changes
        file_list = ", ".join(sorted(affected_files.keys()))
        report_lines.extend([
            "**⚠️ Potentially breaking:** The following JSON files reference removed or renamed gamevals and may need updates:",
            "",
            f"> {file_list}",
            "",
            "---",
            "",
            "### Affected files (details)",
            ""
        ])
        for file_name, file_matches in affected_files.items():
            report_lines.extend([
                "<details>",
                f"<summary><b>{file_name}</b></summary>",
                "",
                "```diff"
            ])
            
            for gameval_name, match_data in sorted(file_matches.items()):
                change_type: str = match_data['change_type']  # type: ignore[assignment]
                line_numbers: List[int] = match_data['line_numbers']  # type: ignore[assignment]
                change_marker = "!" if change_type == 'renamed' else "-"
                change_label = "Renamed" if change_type == 'renamed' else "Removed"
                lines_str = ", ".join(str(ln) for ln in line_numbers)
                report_lines.append(f"{change_marker}{gameval_name} - {change_label} (lines: {lines_str})")
            
            report_lines.extend([
                "```",
                "",
                "</details>",
                ""
            ])
    
    has_any_changes = (
        any(changes['renamed'].values()) or
        any(changes['removed'].values()) or
        any(changes['added'].values())
    )
    
    if has_any_changes:
        report_lines.extend([
            "## Gamevals Changes",
            ""
        ])
        
        all_categories = set(changes['renamed'].keys())
        all_categories.update(changes['removed'].keys())
        all_categories.update(changes['added'].keys())
        
        for category in sorted(all_categories):
            renamed_list = changes['renamed'].get(category, [])
            removed_list = changes['removed'].get(category, [])
            added_list = changes['added'].get(category, [])
            
            if renamed_list or removed_list or added_list:
                total_count = len(renamed_list) + len(removed_list) + len(added_list)
                report_lines.extend([
                    "<details>",
                    f"<summary><b>{category.upper()}</b> ({total_count} changes)</summary>",
                    "",
                    "```diff"
                ])
                
                for old_name, new_name, id_val in renamed_list:
                    report_lines.append(f"! {old_name} → {new_name} (ID: {id_val})")
                
                for name, id_val in removed_list:
                    report_lines.append(f"-{name} (ID: {id_val})")
                
                for name, id_val in added_list:
                    report_lines.append(f"+{name} (ID: {id_val})")
                
                report_lines.extend([
                    "```",
                    "",
                    "</details>",
                    ""
                ])
    else:
        report_lines.append("No changes detected.")
    
    return '\n'.join(report_lines)

def main() -> None:
    """Main function."""
    try:
        result = subprocess.run(
            ['git', 'show', f'HEAD:{GAMEVALS_PATH}'],
            capture_output=True,
            text=True,
            check=True
        )
        old_content = result.stdout
        if old_content.startswith('//'):
            old_content = '\n'.join(old_content.split('\n')[1:])
        old_gamevals = json.loads(old_content)
    except subprocess.CalledProcessError:
        print("Warning: Could not fetch old gamevals.json from git", file=sys.stderr)
        old_gamevals = {}
    
    if not GAMEVALS_PATH.exists():
        print("Error: New gamevals.json not found", file=sys.stderr)
        sys.exit(1)
    
    new_gamevals = load_gamevals(GAMEVALS_PATH)
    changes = compare_gamevals(old_gamevals, new_gamevals)
    report = generate_report(changes)
    print(report)

if __name__ == '__main__':
    main()

