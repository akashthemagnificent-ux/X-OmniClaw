---
name: feishu-doc
description: |
  Feishu document read/write operations. Activate when user mentions Feishu docs, cloud docs, or docx links.
---

# Feishu Document Tool

Single tool `feishu_doc` with action parameter for all document operations.

## Token Extraction

From URL `https://xxx.feishu.cn/docx/ABC123def` → `doc_token` = `ABC123def`

## Actions

### Read Document

```json
{ "action": "read", "doc_token": "ABC123def" }
```

Returns: title, plain text content, block statistics.

### Write Document (Replace All)

```json
{ "action": "write", "doc_token": "ABC123def", "content": "# Title\n\nMarkdown content..." }
```

Replaces entire document with markdown content. Supports: headings, lists, code blocks, quotes, links, images (`![](url)` auto-uploaded), bold/italic/strikethrough.

**Note:** On Android, markdown is converted to Feishu block format using document API.

### Append Content

```json
{ "action": "append", "doc_token": "ABC123def", "content": "Additional content" }
```

Appends markdown to end of document.

### Create Document

```json
{ "action": "create", "title": "New Document", "folder_token": "fldcnXXX" }
```

Creates a new document in specified folder.

## X-OmniClaw Implementation

**Tool Class**: `FeishuDocTools.kt`

**Available Tools**:
- `feishu_doc_read` - Read document content
- `feishu_doc_write` - Write/replace document content
- `feishu_doc_append` - Append content to document
- `feishu_doc_create` - Create new document

**Example Usage**:
```kotlin
// Read document
val result = feishuDocTools.readDoc(docToken = "ABC123def")

// Write document
val result = feishuDocTools.writeDoc(
    docToken = "ABC123def",
    content = "# Title\n\nContent..."
)
```

## Permissions

Required: `docx:document`, `docx:document:readonly`
