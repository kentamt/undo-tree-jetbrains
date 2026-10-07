# Undo Tree for JetBrains

Toby Cubittの **Emacs undo-tree 0.8.2** をIntelliJ IDEA・PyCharmへ移植したプラグインです。
Undo後の別編集で以前のRedo枝を保持し、**ASCIIツリーとEmacsキーを既定**として履歴を操作できます。

[English](README.md) · [操作・設定](docs/USAGE.ja.md) · [移植ノート](docs/PORTING.md) · [変更履歴](CHANGELOG.md)

```text
  o
  |
 / \
x   o
```

`x`が現在の状態、`o`が別の状態です。Undoして別の編集をすると、以前の未来を残して新しい枝を作ります。

- 別の枝を含む、任意の記録状態へ復元。
- 元のASCII配置・ノード文字・選択枝の強調を再現。グラフィカル表示も選択可能。
- Emacs / Standardのキー設定。表示時にツリーへフォーカスを移動。
- 本文を変えない選択モード、差分プレビュー、状態レジスタ、Abort。
- ファイル別の履歴保存と、現在本文の一致を確認した読み込み。

対象は **IntelliJ IDEA / PyCharm 2025.1以降**（Platform build 251以降）です。
利用時にEmacs・Node.jsや、Java・Python言語プラグインは必要ありません。

**開発初期の0.2.0です。** 履歴・ASCII描画・キー定義は元のEmacsと照合しています。
フォーカスを含む実IDEのGUI動作は、両製品での確認が未完了です。

## インストール

IDEの **Settings → Plugins → ⚙ → Install Plugin from Disk…** で
`undo-tree-jetbrains-0.2.0.zip`を**解凍せずに**選び、要求された場合は再起動します。
両IDEで同じZIPを使用します。旧版を導入済みの場合も、この手順で更新できます。

ZIPを手元で作る場合は、Python **3.9以降**・インストール済みIDE・JDK **21以降**を用意します。

```sh
python3 scripts/build.py --ide "/path/to/your/IDE" --test
```

macOSでの例：

```sh
python3 scripts/build.py --ide "/Applications/IntelliJ IDEA.app" --test
```

生成先は`build/distributions/undo-tree-jetbrains-0.2.0.zip`です。
IDE付属のコンパイラを使い、付属していなければ`JAVA_HOME`のJDKを使用します。
Linux / WindowsではIDEのインストールディレクトリを指定してください。
このビルド方法では追加の依存ダウンロードは不要です。

## 使い方

テキストファイルを編集して **Tools → Undo Tree → Undo Tree: Visualize History** を実行します。
表示時にツリーへフォーカスが移り、クリックせずに操作を始められます。

| 操作 | Windows / Linux | macOS |
| --- | --- | --- |
| ツリーを開く | Ctrl+Alt+Z | Cmd+Option+Z |
| Undo TreeのUndo | Ctrl+Alt+U | Cmd+Option+U |
| Undo TreeのRedo | Ctrl+Alt+R | Cmd+Option+R |

Emacsキーが有効でツリーにフォーカスがあるとき：

| キー | 動作 |
| --- | --- |
| ↑ / ↓、`p` / `n`、C-p / C-n | Undo / Redo。Select中は選択移動 |
| ← / →、`b` / `f`、C-b / C-f | Redo枝の選択。Select中は選択移動 |
| C-↑ / C-↓、M-{ / M-} | 分岐点・保存状態・レジスタへ移動 |
| `s`、Enter | Select切り替え、選択状態の復元 |
| `t`、`d`、`v` | 時刻、差分、ASCII / グラフィカル表示の切り替え |
| `q`、C-q | 閉じる。Abortは表示開始時の状態へ復元 |

**CはmacOSでもControl、MはAlt / Option**です。
既定値は **Settings → Tools → Undo Tree** で変更できます。
ツールバーの表示・キー切り替えは、その表示セッションに適用します。

通常のCmd/Ctrl+ZはIDEのUndoのままです。日常のキーでツリーを使う場合は、
**Settings → Keymap** で **Undo Tree: Undo / Redo** に割り当ててください。
IDE側のUndoとは編集のまとまり方が異なる場合があり、記録開始前の履歴は取り込みません。

履歴保存は既定で有効です。保存ファイルはIDEのsystemディレクトリ内の`undo-tree/`にあり、
**過去の本文を含みます**。Emacs・VS Codeの履歴ファイルとの互換性、選択範囲だけのUndoは未実装です。
詳細は[操作・設定](docs/USAGE.ja.md)を参照してください。

## 開発

```sh
python3 scripts/test.py --jdk "/path/to/jdk-21-or-newer"
```

49履歴トレース・8描画ケース・2種類のキー定義を元のEmacsと比較し、
保存形式と5,000回のランダム操作も検証します。Emacsがあれば実行時比較を追加します。
これらを実行するGitHub Actionsも用意しています。

[開発ガイド](CONTRIBUTING.md) · [GUI確認手順](docs/MANUAL_TEST.md) · [公開手順](docs/RELEASING.md)

## 出典・ライセンス

[Toby Cubittのundo-tree](https://www.dr-qubit.org/undo-tree.html)を、
[VS Code移植版](https://github.com/kentamt/undo-tree-vscode)を参考に移植しています。
元のソース・出典情報は[upstream](upstream/README.md)に保持しています。

**GPL-3.0-or-later**。[LICENSE](LICENSE)・[NOTICE](NOTICE)を参照してください。
