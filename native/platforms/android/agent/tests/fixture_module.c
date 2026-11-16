/* 主机单测夹具：一个带 Build ID 的小 so，验证"基址 + st_value = 运行地址"和内存里读 Build ID。 */
int fixture_function(int x) { return x * 3 + 1; }
