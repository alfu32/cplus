#include <stdio.h>

comptime import "stdlib:/ui/printf_renderer.cp";

int main(void) {
    ui_vnode_t* root = ui_vnode_t.element("panel");
    ui_vnode_t* title = ui_vnode_t.text("Hello from C+");
    if (root == NULL || title == NULL) return 1;

    root->style.layout.set(UI_POSITION_RELATIVE_PARENT, 10, 20, 320, 120);
    root->style.graphics.background("#202020");
    root->style.graphics.color("white");

    title->style.layout.set(UI_POSITION_RELATIVE_PARENT, 8, 12, 200, 24);
    title->style.graphics.font_family("mono");
    title->style.graphics.font_size(16);

    if (root->child(title) != 0) {
        title->destroy();
        root->destroy();
        return 1;
    }

    ui_printf_renderer_t printer;
    if (printer.init(stdout) != 0) {
        root->destroy();
        return 1;
    }

    ui_renderer_t renderer = printer.interface();
    int error = renderer.render(root, 800, 600);

    root->destroy();
    return error;
}
