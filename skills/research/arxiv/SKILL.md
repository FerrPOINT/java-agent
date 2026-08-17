---
name: arxiv
description: "Search arXiv papers by keyword, author, category, or ID."
version: 1.0.0
author: Hermes Agent
license: MIT
platforms: [linux, macos, windows]
metadata:
  hermes:
    tags: [Research, Arxiv, Papers, Academic, Science, API]
    related_skills: []
---

# arXiv Research

## Overview

Search arXiv papers by keyword, author, category, or ID using the arXiv API.

## When to Use

- User asks to find research papers
- User wants to search arXiv by topic
- User needs paper abstracts or metadata

## Workflow

1. **Search:** Use the arXiv API via `terminal` with `curl`:
   ```bash
   curl -s "http://export.arxiv.org/api/query?search_query=all:QUERY&max_results=5"
   ```

2. **Parse:** Extract paper titles, authors, abstracts, and URLs from the Atom XML response.

3. **Summarize:** Present results in a readable format with links.

## Search Parameters

- `all:QUERY` — search all fields
- `ti:QUERY` — title only
- `au:QUERY` — author only
- `cat:CATEGORY` — category filter (e.g., cs.AI, physics)
- `id_list:ID` — specific paper ID

## Example

```bash
# Search for papers about transformer architectures
curl -s "http://export.arxiv.org/api/query?search_query=ti:transformer&max_results=5&sortBy=relevance"
```

## Notes

- The arXiv API is rate-limited (1 request per 3 seconds)
- Results are in Atom XML format
- Use `web_extract` or parse XML directly with `terminal` tools