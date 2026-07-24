#!/usr/bin/env python3
"""
Generate mapping delta files by comparing Yarn mapping dumps.

BUILD-TIME TOOL: compares two structured Yarn mapping dumps and outputs
an incremental delta. This is NOT related to the runtime flat-format
mapping files (e.g. mappings/fabric/v1_20_1.json) used by MappingLoader.

Input format (structured Yarn dump, per version):
  {
    "version": "1.20.2",
    "classes": { ... },
    "methods": { ... },
    "fields":  { ... },
    "packets": { ... }
  }

Input path: mappings/fabric/yarn/v{version}.json
Output path: mappings/fabric/deltas/v{version}.json

Usage: python generate_mappings.py <base_version> <target_version>
Example: python generate_mappings.py 1.20.1 1.20.2
"""

import json
import sys
import os
from pathlib import Path

def load_mapping(version: str) -> dict:
    """Load structured Yarn mapping dump for a version."""
    mapping_path = Path(__file__).parent.parent / "mappings" / "fabric" / "yarn" / f"v{version}.json"
    if not mapping_path.exists():
        raise FileNotFoundError(f"Mapping file not found: {mapping_path}")
    with open(mapping_path, 'r') as f:
        return json.load(f)

def compare_mappings(base: dict, target: dict) -> dict:
    """Compare two mappings and generate delta."""
    delta = {
        "platform": "fabric",
        "version": target["version"],
        "base_version": base["version"],
        "delta_changes": {
            "classes": {},
            "methods": {},
            "fields": {},
            "packets": {}
        },
        "notes": f"Incremental delta patch from {base['version']} to {target['version']}"
    }
    
    # Compare classes
    for key in set(target["classes"].keys()) - set(base["classes"].keys()):
        delta["delta_changes"]["classes"][key] = target["classes"][key]
    
    # Compare methods
    for key in set(target["methods"].keys()) - set(base["methods"].keys()):
        delta["delta_changes"]["methods"][key] = target["methods"][key]
    
    # Compare fields
    for key in set(target["fields"].keys()) - set(base["fields"].keys()):
        delta["delta_changes"]["fields"][key] = target["fields"][key]
    
    # Compare packets
    for key in set(target["packets"].keys()) - set(base["packets"].keys()):
        delta["delta_changes"]["packets"][key] = target["packets"][key]
    
    return delta

def main():
    if len(sys.argv) != 3:
        print("Usage: python generate_mappings.py <base_version> <target_version>")
        print("Example: python generate_mappings.py 1.20.1 1.20.2")
        sys.exit(1)
    
    base_version = sys.argv[1].replace(".", "_")
    target_version = sys.argv[2].replace(".", "_")
    
    try:
        base_mapping = load_mapping(base_version)
        target_mapping = load_mapping(target_version)
        
        delta = compare_mappings(base_mapping, target_mapping)
        
        output_path = Path(__file__).parent.parent / "mappings" / "fabric" / "deltas" / f"v{target_version}.json"
        output_path.parent.mkdir(parents=True, exist_ok=True)
        
        with open(output_path, 'w') as f:
            json.dump(delta, f, indent=2)
        
        print(f"Delta mapping generated: {output_path}")
        print(f"Changed classes: {len(delta['delta_changes']['classes'])}")
        print(f"Changed methods: {len(delta['delta_changes']['methods'])}")
        print(f"Changed fields: {len(delta['delta_changes']['fields'])}")
        print(f"Changed packets: {len(delta['delta_changes']['packets'])}")
        
    except FileNotFoundError as e:
        print(f"Error: {e}")
        sys.exit(1)
    except Exception as e:
        print(f"Error generating delta: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()
