package com.works.controller;

import com.works.entity.Product;
import com.works.service.CryptoService;
import com.works.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("product")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final CryptoService cryptoService;

    @PostMapping("save")
    public Product save(@Valid @RequestBody Product product) {
        return productService.save(product);
    }

    @GetMapping("list")
    public List<Product> list() {
        String data = "12345";
        String chipperText = cryptoService.encrypt(data);
        System.out.println("chipperText = " + chipperText);
        String plainText = cryptoService.decrypt(chipperText);
        System.out.println("plainText = " + plainText);

        return productService.list();
    }

}
