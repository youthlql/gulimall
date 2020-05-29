package com.atguigu.gulimall.cart.controller;

import com.atguigu.gulimall.cart.interceptor.CartInterceptor;
import com.atguigu.gulimall.cart.service.CartService;
import com.atguigu.gulimall.cart.vo.CartItem;
import com.atguigu.gulimall.cart.vo.UserInfoTo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * @author: maruimin
 * @date: 2020/5/28 22:03
 */
@Controller
public class CartController {

    @Autowired
    CartService cartService;

    @GetMapping("/cart.html")
    public String cartListPage() {
        // 1. 快速得到用户信息:id, user-key等
        UserInfoTo userInfoTo = CartInterceptor.threadLocal.get();
        System.out.println(userInfoTo);
        return "cartList";
    }

    @GetMapping("/addToCart")
    public String addToCart(@RequestParam("skuId") Long skuId,
                            @RequestParam("num") Integer num,
                            Model model) {
        CartItem cartItem = cartService.addToCart(skuId, num);
        model.addAttribute("item", cartItem);
        return "success";
    }
}