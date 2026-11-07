# Wiki maintenance

The short root README introduces Assert SQL. Detailed guides are published at
[the GitHub wiki](https://github.com/icher-g/assert-sql/wiki), with their Markdown
sources versioned in [docs/wiki](wiki) so documentation changes can be reviewed
alongside code.

Edit the files in `docs/wiki`, including `Home.md` and `_Sidebar.md` when adding
or renaming a guide. Use absolute GitHub wiki links between pages and absolute
repository links to examples; those links work in both locations. Page filenames
without `.md` become wiki URL paths. Keep the root README examples brief and link
to the relevant guide for contracts and limitations.

GitHub stores the published wiki in a separate Git repository. After reviewing
the source changes, clone it into a sibling directory and copy the page sources:

```powershell
git clone https://github.com/icher-g/assert-sql.wiki.git ../assert-sql-wiki
Copy-Item docs/wiki/*.md ../assert-sql-wiki/
git -C ../assert-sql-wiki diff --check
git -C ../assert-sql-wiki diff
git -C ../assert-sql-wiki add -- '*.md'
git -C ../assert-sql-wiki commit -m "Update user guides"
git -C ../assert-sql-wiki push origin HEAD
```

For an existing clone, pull the latest wiki changes before copying. A renamed or
removed source page also needs the corresponding published file renamed or
removed explicitly; copying only adds and replaces pages. Verify navigation and
repository links after publishing. Publishing is manual; merging the source
files into the main repository does not update the wiki automatically.
