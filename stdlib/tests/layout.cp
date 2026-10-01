#include <stdio.h>
#include <string.h>
comptime import "stdlib:/ui/printf_renderer.cp";

@test "vnode layout style defaults are explicit zero boxes" {
    ui_vnode_t* node = ui_vnode_t.element("box");
    @assert(node != NULL);
    @assert(node->style.layout.position == UI_POSITION_RELATIVE_PARENT);
    @assert(node->style.layout.top == 0.0);
    @assert(node->style.layout.left == 0.0);
    @assert(node->style.layout.width == 0.0);
    @assert(node->style.layout.height == 0.0);
    node->destroy();
}

@test "relative-parent resolves from parent top-left" {
    ui_vnode_t* root = ui_vnode_t.element("root");
    ui_vnode_t* child = ui_vnode_t.element("child");
    @assert(root->layout( UI_POSITION_RELATIVE_PARENT, 20, 10, 300, 200) == 0);
    @assert(child->layout( UI_POSITION_RELATIVE_PARENT, 7, 5, 30, 40) == 0);
    @assert(UI_CHILD(root, child) == 0);
    ui_layout_result_t layout; memset(&layout,0,sizeof(layout)); @assert(layout.init() == 0);
    @assert(layout.run(root, 800, 600) == 0);
    @assert(layout.drawables.length == 2);
    @assert(layout.drawables.items[0].as.box.rect.x == 10.0);
    @assert(layout.drawables.items[0].as.box.rect.y == 20.0);
    @assert(layout.drawables.items[1].as.box.rect.x == 15.0);
    @assert(layout.drawables.items[1].as.box.rect.y == 27.0);
    @assert(layout.drawables.items[1].as.box.rect.width == 30.0);
    @assert(layout.drawables.items[1].as.box.rect.height == 40.0);
    layout.destroy(); root->destroy();
}

@test "relative-sibling anchors to previous sibling top-left without flow" {
    ui_vnode_t* root = ui_vnode_t.element("root");
    ui_vnode_t* first = ui_vnode_t.element("first"); ui_vnode_t* second = ui_vnode_t.element("second");
    @assert(root->layout( UI_POSITION_RELATIVE_PARENT, 100, 50, 400, 300) == 0);
    @assert(first->layout( UI_POSITION_RELATIVE_PARENT, 20, 10, 100, 80) == 0);
    @assert(second->layout( UI_POSITION_RELATIVE_SIBLING, 4, 3, 20, 10) == 0);
    @assert(UI_CHILD(root, first) == 0); @assert(UI_CHILD(root, second) == 0);
    ui_layout_result_t layout; memset(&layout,0,sizeof(layout)); @assert(layout.init() == 0);
    @assert(layout.run(root, 800, 600) == 0);
    @assert(layout.drawables.length == 3);
    @assert(layout.drawables.items[1].as.box.rect.x == 60.0);
    @assert(layout.drawables.items[1].as.box.rect.y == 120.0);
    @assert(layout.drawables.items[2].as.box.rect.x == 63.0);
    @assert(layout.drawables.items[2].as.box.rect.y == 124.0);
    @assert(layout.drawables.items[2].as.box.rect.x != 160.0);
    layout.destroy(); root->destroy();
}

@test "fixed anchors to viewport rather than parent" {
    ui_vnode_t* root = ui_vnode_t.element("root"); ui_vnode_t* fixed = ui_vnode_t.element("fixed");
    @assert(root->layout( UI_POSITION_RELATIVE_PARENT, 100, 200, 400, 300) == 0);
    @assert(fixed->layout( UI_POSITION_FIXED, 11, 9, 20, 30) == 0);
    @assert(UI_CHILD(root, fixed) == 0);
    ui_layout_result_t layout; memset(&layout,0,sizeof(layout)); @assert(layout.init() == 0);
    @assert(layout.run(root, 800, 600) == 0);
    @assert(layout.drawables.items[1].as.box.rect.x == 9.0);
    @assert(layout.drawables.items[1].as.box.rect.y == 11.0);
    layout.destroy(); root->destroy();
}

@test "layout emits styled boxes text child indices and images" {
    int pixels = 1234;
    ui_vnode_t* root = ui_vnode_t.element("panel"); ui_vnode_t* text = ui_vnode_t.text_node("hello"); ui_vnode_t* image = ui_vnode_t.image(&pixels);
    @assert(root->layout( UI_POSITION_RELATIVE_PARENT, 2, 1, 100, 50) == 0);
    @assert(root->background( "#101010") == 0);
    @assert(root->color( "white") == 0);
    @assert(root->border( "1 solid gray") == 0);
    @assert(root->font_family( "mono") == 0);
    @assert(root->font_size( 14) == 0);
    @assert(root->font_weight( 700) == 0);
    @assert(root->font_decoration( UI_FONT_DECORATION_UNDERLINE) == 0);
    @assert(text->layout( UI_POSITION_RELATIVE_PARENT, 4, 3, 40, 12) == 0);
    @assert(image->layout( UI_POSITION_RELATIVE_PARENT, 20, 5, 32, 16) == 0);
    @assert(UI_CHILD(root, text) == 0); @assert(UI_CHILD(root, image) == 0);
    ui_layout_result_t layout; memset(&layout,0,sizeof(layout)); @assert(layout.init() == 0);
    @assert(layout.run(root, 800, 600) == 0);
    @assert(layout.drawables.length == 3);
    @assert(layout.drawables.items[0].kind == UI_DRAWABLE_BOX);
    @assert(layout.drawables.items[0].as.box.children.length == 2);
    @assert(layout.drawables.items[0].as.box.children.items[0] == 1);
    @assert(layout.drawables.items[0].as.box.children.items[1] == 2);
    @assert(strcmp(layout.drawables.items[0].as.box.style->background, "#101010") == 0);
    @assert(layout.drawables.items[1].kind == UI_DRAWABLE_BOX);
    @assert(strcmp(layout.drawables.items[1].as.box.text, "hello") == 0);
    @assert(layout.drawables.items[2].kind == UI_DRAWABLE_IMAGE);
    @assert(layout.drawables.items[2].as.image.bitmap == &pixels);
    layout.destroy(); root->destroy();
}

@test "printf renderer prints layout emissions" {
    int pixels = 1;
    ui_vnode_t* root = ui_vnode_t.element("panel"); ui_vnode_t* text = ui_vnode_t.text_node("hello"); ui_vnode_t* image = ui_vnode_t.image(&pixels);
    @assert(root->layout( UI_POSITION_RELATIVE_PARENT, 2, 1, 100, 50) == 0);
    @assert(root->background( "blue") == 0);
    @assert(text->layout( UI_POSITION_RELATIVE_PARENT, 4, 3, 40, 12) == 0);
    @assert(image->layout( UI_POSITION_RELATIVE_PARENT, 20, 5, 32, 16) == 0);
    @assert(UI_CHILD(root, text) == 0); @assert(UI_CHILD(root, image) == 0);
    FILE* file = tmpfile(); @assert(file != NULL);
    ui_printf_renderer_state_t state; ui_renderer_t renderer = state.renderer(file);
    @assert(renderer.render(root, 800, 600) == 0);
    rewind(file);
    char output[2048]; size_t n = fread(output,1,sizeof(output)-1,file); output[n] = '\0';
    @assert(strstr(output, "LAYOUT drawables=3") != NULL);
    @assert(strstr(output, "BOX node=0") != NULL);
    @assert(strstr(output, "x=1.00 y=2.00 w=100.00 h=50.00") != NULL);
    @assert(strstr(output, "background=\"blue\"") != NULL);
    @assert(strstr(output, "text=\"hello\"") != NULL);
    @assert(strstr(output, "IMAGE node=0") != NULL);
    fclose(file); root->destroy();
}
