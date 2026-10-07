# 操作・設定

[README](../README.ja.md) · [English overview](../README.md)

## 使い方

テキストファイルを開いて編集し、**Tools → Undo Tree → Undo Tree: Visualize History** を実行します。エディタの右クリックメニュー、Find Action、**View → Tool Windows → Undo Tree** からもアクセスできます。

| 操作 | Windows / Linux | macOS |
| --- | --- | --- |
| ツリーを開く | Ctrl+Alt+Z | Cmd+Option+Z |
| Undo TreeでUndo | Ctrl+Alt+U | Cmd+Option+U |
| Undo TreeでRedo | Ctrl+Alt+R | Cmd+Option+R |

**通常のCmd/Ctrl+ZはIDEのUndoのままです。** 日常のUndo/Redoもこのツリーで行う場合は、Settings → Keymapで **Undo Tree: Undo / Redo** に普段のキーを割り当て、競合するIDEの割り当てを外してください。IDEのUndo/Redoとプラグインの編集グループが一致しない場合、IDEのUndoは新しい履歴状態として記録されます。記録開始前のIDE履歴は取り込みません。

ツリーを開くと、そのタブ内のツリーへ自動的にフォーカスを移します。クリックせずに操作を始められます。復元・枝移動・表示切り替え・差分表示後もツリーへフォーカスを戻します。

既定の **Emacsキー** では、元のvisualizer / selection-mode keymapに対応する次のキーが使えます。**Settings → Tools → Undo Tree → Visualizer keybindings** でEmacs / Standardを選べます。ツールバーの **Emacs keys** は、その表示セッションだけの切り替えです。

| キー | 動作 |
| --- | --- |
| ↑ / ↓、p / n、Control+P / N | Undo / Redo。Select中は選択だけを移動 |
| ← / →、b / f、Control+B / F | Redo枝を選択。Select中は同じ行の状態を選択 |
| Control+↑ / ↓、Alt+Shift+[ / ]（M-{ / M-}） | 前 / 次の分岐点・保存状態・レジスタへ移動 |
| S | Selectモードを切り替え |
| Enter | 選択状態を復元 |
| T | 相対時刻表示を切り替え |
| D | 差分を表示 / 非表示。Select中は選択状態、それ以外は親状態と比較 |
| V | グラフィカル / テキスト表示を切り替え |
| Q / Escape | ツリーを閉じる。本文は現在の状態を保持 |
| Control+Q | セッション開始時の状態へ戻して閉じる |
| PageUp / PageDown | スクロール。Select中は上下へ10状態移動 |
| , / .、< / > | 左右へスクロール。Select中は同じ行で10状態移動 |
| Control+V / Alt+V | PageDown / PageUpの追加エイリアス |

macOSでも上記のEmacsキーは **Control**、Metaは **Alt / Option** です。キーはツリーにフォーカスがある場合だけ有効です。Standardではp/n/b/f、Control+P/N/B/F、Metaキー、Control+Q、Control+V / Alt+Vを割り当てません。

ASCIIは元の`o` / `x` / `s` / レジスタ文字、2行の接続線、分岐の文字間隔を再現し、アクティブな枝を太字で表示します。`ASCII`でグラフィカル表示と切り替えられます。クリックは通常モードでは即時復元、Selectモードでは選択のみを行います。Enterで復元するとSelectを終了します。`Time` はテキスト表示の相対時刻を切り替えます。差分はツリー下部に表示し、移動に合わせて更新します。Q / Abortで閉じると本文エディタへフォーカスを戻します。

`Register` / `Recall` で、そのファイルの状態を1文字のレジスタへ登録・復元できます。レジスタはセッション中だけ有効です。

ツールウィンドウを開いたときがAbortの開始点です。通常の編集が新しい履歴状態を作ると、開始点もその編集後へ更新します。開始点が履歴の整理で消えた場合、Abortは復元せずに閉じます。

## 設定と保存

**Settings → Tools → Undo Tree** で設定します。

| 設定 | 既定値 | 内容 |
| --- | --- | --- |
| Tree display | Emacs ASCII | ASCII / Graphicalの既定表示 |
| Visualizer keybindings | Emacs | Emacs / Standardのツリー操作キー |
| Typing group delay | 600 ms | 連続編集をまとめる間隔。0で各ドキュメントイベントを独立記録 |
| Maximum states per file | 200 | 履歴数の目安。現在状態と最後のUndoを保護するため、超える場合あり |
| Maximum file size | 1,000,000 | UTF-16コード単位。超えるファイルは追跡対象外 |
| Save history across IDE sessions | 有効 | 保存・ファイルを閉じる・プロジェクト終了時に履歴を書き出す |

グループ間隔と履歴数の変更は、新しく追跡するファイルから適用します。履歴はIDEのsystemディレクトリ内の `undo-tree/` に保存し、プロジェクトのソースファイル横には書き込みません。**過去の本文を含みます。** 保存を無効にしても、既存の保存ファイルは削除されません。不要ならIDEを終了してこのディレクトリを削除してください。

保存本文のSHA-256が現在の本文と一致する履歴だけを読み込みます。外部で変更されたファイルや不正な保存データは、新しい履歴から開始します。IDEが未保存本文を復旧しなければ、その未保存本文に対応する履歴は読み込みません。保存データは64 MiB、復元中の本文は1,000万UTF-16単位までとし、検証処理量にも上限を設けています。極端に大きい履歴は保存・読み込みを行えないことがあります。

